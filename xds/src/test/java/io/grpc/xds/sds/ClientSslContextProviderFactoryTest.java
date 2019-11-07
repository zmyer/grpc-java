/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds.sds;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Strings;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ClientSslContextProviderFactory}. */
@RunWith(JUnit4.class)
public class ClientSslContextProviderFactoryTest {

  private static final String CLIENT_PEM_FILE = "client.pem";
  private static final String CLIENT_KEY_FILE = "client.key";
  private static final String CA_PEM_FILE = "ca.pem";

  ClientSslContextProviderFactory clientSslContextProviderFactory =
      new ClientSslContextProviderFactory();

  static CommonTlsContext buildCommonTlsContextFromSdsConfigForTlsCertificate(
      String name, String targetUri, String trustCa) {

    ApiConfigSource apiConfigSource =
        ApiConfigSource.newBuilder()
            .setApiType(ApiType.GRPC)
            .addGrpcServices(
                GrpcService.newBuilder()
                    .setGoogleGrpc(GoogleGrpc.newBuilder().setTargetUri(targetUri).build())
                    .build())
            .build();

    SdsSecretConfig sdsSecretConfig =
        SdsSecretConfig.newBuilder()
            .setName(name)
            .setSdsConfig(ConfigSource.newBuilder().setApiConfigSource(apiConfigSource).build())
            .build();

    CommonTlsContext.Builder builder =
        CommonTlsContext.newBuilder().addTlsCertificateSdsSecretConfigs(sdsSecretConfig);

    if (!Strings.isNullOrEmpty(trustCa)) {
      builder.setValidationContext(
          CertificateValidationContext.newBuilder()
              .setTrustedCa(DataSource.newBuilder().setFilename(trustCa))
              .build());
    }
    return builder.build();
  }

  static CommonTlsContext buildCommonTlsContextFromSdsConfigForValidationContext(
      String name, String targetUri, String privateKey, String certChain) {

    ApiConfigSource apiConfigSource =
        ApiConfigSource.newBuilder()
            .setApiType(ApiType.GRPC)
            .addGrpcServices(
                GrpcService.newBuilder()
                    .setGoogleGrpc(GoogleGrpc.newBuilder().setTargetUri(targetUri).build())
                    .build())
            .build();

    SdsSecretConfig sdsSecretConfig =
        SdsSecretConfig.newBuilder()
            .setName(name)
            .setSdsConfig(ConfigSource.newBuilder().setApiConfigSource(apiConfigSource).build())
            .build();

    CommonTlsContext.Builder builder =
        CommonTlsContext.newBuilder().setValidationContextSdsSecretConfig(sdsSecretConfig);

    if (!Strings.isNullOrEmpty(privateKey) && !Strings.isNullOrEmpty(certChain)) {
      builder.addTlsCertificates(
          TlsCertificate.newBuilder()
              .setCertificateChain(DataSource.newBuilder().setFilename(certChain))
              .setPrivateKey(DataSource.newBuilder().setFilename(privateKey))
              .build());
    }
    return builder.build();
  }

  @Test
  public void createSslContextProvider_allFilenames() {
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SslContextProvider<UpstreamTlsContext> sslContextProvider =
        clientSslContextProviderFactory.createSslContextProvider(upstreamTlsContext);
    assertThat(sslContextProvider).isNotNull();
  }

  @Test
  public void createSslContextProvider_sdsConfigForTlsCert_expectException() {
    CommonTlsContext commonTlsContext =
        buildCommonTlsContextFromSdsConfigForTlsCertificate(
            "name", "unix:/tmp/sds/path", CA_PEM_FILE);
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContext(commonTlsContext);

    try {
      SslContextProvider<UpstreamTlsContext> unused =
          clientSslContextProviderFactory.createSslContextProvider(upstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("UpstreamTlsContext using SDS not supported");
    }
  }

  @Test
  public void createSslContextProvider_sdsConfigForCertValidationContext_expectException() {
    CommonTlsContext commonTlsContext =
        buildCommonTlsContextFromSdsConfigForValidationContext(
            "name", "unix:/tmp/sds/path", CLIENT_KEY_FILE, CLIENT_PEM_FILE);
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContext(commonTlsContext);

    try {
      SslContextProvider<UpstreamTlsContext> unused =
          clientSslContextProviderFactory.createSslContextProvider(upstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("UpstreamTlsContext using SDS not supported");
    }
  }
}
