/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a.auth.delegation;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.s3a.AWSCredentialProviderList;
import org.apache.hadoop.fs.s3a.Invoker;
import org.apache.hadoop.fs.s3a.Retries;
import org.apache.hadoop.fs.s3a.S3ARetryPolicy;
import org.apache.hadoop.fs.s3a.S3AUtils;
import org.apache.hadoop.fs.s3a.auth.MarshalledCredentialProvider;
import org.apache.hadoop.fs.s3a.auth.MarshalledCredentials;
import org.apache.hadoop.fs.s3a.auth.RoleModel;
import org.apache.hadoop.fs.s3a.auth.STSClientFactory;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.Text;

import static org.apache.hadoop.fs.s3a.Constants.AWS_CREDENTIALS_PROVIDER;
import static org.apache.hadoop.fs.s3a.Invoker.once;
import static org.apache.hadoop.fs.s3a.S3AUtils.STANDARD_AWS_PROVIDERS;
import static org.apache.hadoop.fs.s3a.S3AUtils.buildAWSProviderList;
import static org.apache.hadoop.fs.s3a.auth.delegation.DelegationConstants.*;

/**
 * The session token DT binding: creates an AWS session token
 * for the DT, extracts and serves it up afterwards.
 */
public class SessionTokenBinding extends AbstractDelegationTokenBinding {

  private static final Logger LOG = LoggerFactory.getLogger(
      SessionTokenBinding.class);

  /**
   * Wire name of this binding: {@value}.
   */
  private static final String NAME = "SessionTokens/001";

  /**
   * A message added to the standard origin string when the DT is
   * built from session credentials passed in.
   */
  @VisibleForTesting
  public static final String CREDENTIALS_CONVERTED_TO_DELEGATION_TOKEN
      = "Existing session credentials converted to Delegation Token";

  /** Invoker for STS calls. */
  private Invoker invoker;

  /**
   * Has an attempt to initialize STS been attempted?
   */
  private final AtomicBoolean stsInitAttempted = new AtomicBoolean(false);

  /** The STS client; created in startup if the parental credentials permit. */
  @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
  private Optional<STSClientFactory.STSClient> stsClient = Optional.empty();

  /**
   * duration of session.
   */
  private long duration;

  /**
   * Flag to indicate that the auth chain provides session credentials.
   * If true it means that STS cannot be used (and stsClient is null).
   */
  private boolean hasSessionCreds;

  /**
   * The auth chain for the parent options.
   */
  private AWSCredentialProviderList parentAuthChain;

  /**
   * Has a log message about forwarding credentials been printed yet?
   */
  private final AtomicBoolean forwardMessageLogged = new AtomicBoolean(false);

  private String endpoint;

  private String region;

  /** Constructor for reflection. */
  public SessionTokenBinding() {
    this(NAME, SESSION_TOKEN_KIND);
  }

  /**
   * Constructor for subclasses.
   * @param name binding name.
   * @param kind token kind.
   */
  protected SessionTokenBinding(final String name,
      final Text kind) {
    super(name, kind);
  }

  /**
   * Service start will read in all configuration options
   * then build that client.
   */
  @Override
  protected void serviceStart() throws Exception {
    super.serviceStart();
    Configuration conf = getConfig();
    duration = conf.getTimeDuration(DELEGATION_TOKEN_DURATION,
        DEFAULT_DELEGATION_TOKEN_DURATION,
        TimeUnit.SECONDS);
    endpoint = conf.getTrimmed(DELEGATION_TOKEN_ENDPOINT,
        DEFAULT_DELEGATION_TOKEN_ENDPOINT);
    region = conf.getTrimmed(DELEGATION_TOKEN_REGION,
        DEFAULT_DELEGATION_TOKEN_REGION);

    // create the provider set for session credentials.
    parentAuthChain = buildAWSProviderList(
        Optional.of(getCanonicalUri()),
        conf,
        AWS_CREDENTIALS_PROVIDER,
        STANDARD_AWS_PROVIDERS,
        new HashSet<>());
  }

  @Override
  protected void serviceStop() throws Exception {
    super.serviceStop();
    // this is here to keep findbugs quiet, even though nothing
    // can safely invoke stsClient as we are shut down.
    synchronized (this) {
      this.stsClient.ifPresent(IOUtils::closeStream);
      this.stsClient = Optional.empty();
    }
  }

  /**
   * Return an unbonded provider chain.
   * @return the auth chain built from the assumed role credentials
   * @throws IOException any failure.
   */
  @Override
  public AWSCredentialProviderList deployUnbonded()
      throws IOException {
    requireServiceStarted();
    return parentAuthChain;
  }

  /**
   * Get the invoker for STS calls.
   * @return the invoker
   */
  protected Invoker getInvoker() {
    return invoker;
  }

  @Override
  public AWSCredentialProviderList bindToTokenIdentifier(
      final AbstractS3ATokenIdentifier retrievedIdentifier)
      throws IOException {
    SessionTokenIdentifier tokenIdentifier =
        convertTokenIdentifier(retrievedIdentifier,
            SessionTokenIdentifier.class);
    return new AWSCredentialProviderList(
        new MarshalledCredentialProvider(
            getFileSystem().getUri(),
            getConfig(),
            tokenIdentifier.getMarshalledCredentials(),
            MarshalledCredentials.CredentialTypeRequired.SessionOnly));
  }


  @Override
  public String getDescription() {
    return String.format(bindingName() 
            + "%s token binding for user %s," +
            "with STS endpoint \"%s\", region \"%s\" and token duration %s",
        bindingName(), getOwner().getShortUserName(), endpoint, region, duration);
  }

  /**
   * Get the role of this token; subclasses should override this
   * for better logging.
   * @return the role of this token
   */
  protected String bindingName() {
    return "Session";
  }

  /**
   * Attempt to init the STS connection, only does it once.
   * If the AWS credential list to this service return session credentials
   * then this method will return {@code empty()}; no attempt is
   * made to connect to STS.
   * Otherwise, the STS binding info will be looked up and an attempt
   * made to connect to STS.
   * Only one attempt will be made.
   * @return any STS client created.
   * @throws IOException any failure to bind to STS.
   */
  private synchronized Optional<STSClientFactory.STSClient> maybeInitSTS()
      throws IOException {
    if (stsInitAttempted.getAndSet(true)) {
      // whether or not it succeeded, the state of the STS client is what
      // callers get after the first attempt.
      return stsClient;
    }

    Configuration conf = getConfig();
    URI uri = getCanonicalUri();

    // Ask the owner for any session credentials which it already has
    // so that it can just propagate them.
    // this call may fail if there are no credentials on the auth
    // chain.
    // As no codepath (session propagation, STS creation) will work,
    // throw this.
    final AWSCredentials parentCredentials = once("get credentials",
        "",
        () -> parentAuthChain.getCredentials());
    hasSessionCreds = parentCredentials instanceof AWSSessionCredentials;

    if (!hasSessionCreds) {
      LOG.info("Creating STS client for {}", getDescription());

      invoker = new Invoker(new S3ARetryPolicy(conf), LOG_EVENT);
      ClientConfiguration awsConf =
          S3AUtils.createAwsConf(conf, uri.getHost());
      AWSSecurityTokenService tokenService =
          STSClientFactory.builder(parentAuthChain,
              awsConf,
              endpoint,
              region)
              .build();
      stsClient = Optional.of(
          STSClientFactory.createClientConnection(tokenService, invoker));
    } else {
      LOG.debug("Parent-provided session credentials will be propagated");
      stsClient = Optional.empty();
    }
    return stsClient;
  }

  /**
   * Log retries at debug.
   */
  public static final Invoker.Retried LOG_EVENT =
      (text, exception, retries, idempotent) -> {
    LOG.info("{}: " + exception, text);
    if (retries == 1) {
      // stack on first attempt, to keep noise down
      LOG.debug("{}: " + exception, text, exception);
    }
  };
  
  /**
   * Get the client to AWS STS.
   * @return the STS client, when successfully inited.
   */
  protected Optional<STSClientFactory.STSClient> prepareSTSClient()
      throws IOException {
    return maybeInitSTS();
  }

  /**
   * Duration of sessions.
   * @return duration in seconds.
   */
  public long getDuration() {
    return duration;
  }

  @Override
  @Retries.RetryTranslated
  public SessionTokenIdentifier createTokenIdentifier(
      final Optional<RoleModel.Policy> policy,
      final EncryptionSecrets encryptionSecrets) throws IOException {
    requireServiceStarted();

    final MarshalledCredentials marshalledCredentials;
    String origin = AbstractS3ATokenIdentifier.createDefaultOriginMessage();
    final Optional<STSClientFactory.STSClient> client = prepareSTSClient();

    if (client.isPresent()) {
      // this is the normal route: ask for a new STS token
      marshalledCredentials = new MarshalledCredentials(
          "Session Token", client.get()
              .requestSessionCredentials(duration, TimeUnit.SECONDS));
    } else {
      // get a new set of parental session credentials (pick up IAM refresh)
      if (!forwardMessageLogged.getAndSet(true)) {
        // warn caller on the first -and only the first- use.
        LOG.warn("Forwarding existing session credentials to {}"
            + " -duration unknown", getCanonicalUri());
      }
      origin += " " + CREDENTIALS_CONVERTED_TO_DELEGATION_TOKEN;
      final AWSCredentials awsCredentials
          = parentAuthChain.getCredentials();
      if (awsCredentials instanceof AWSSessionCredentials) {
        marshalledCredentials = new MarshalledCredentials(
            "Session Token", (AWSSessionCredentials) awsCredentials);
      } else {
        throw new DelegationTokenIOException(
            "AWS Authentication chain is no longer supplying session secrets");
      }
    }
    return new SessionTokenIdentifier(getKind(),
        getOwnerText(),
        getCanonicalUri(),
        marshalledCredentials,
        encryptionSecrets,
        origin);
  }

  @Override
  public SessionTokenIdentifier createEmptyIdentifier() {
    return new SessionTokenIdentifier();
  }

}
