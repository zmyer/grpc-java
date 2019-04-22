/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc.internal;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.NameResolver;
import io.grpc.ProxyDetector;
import java.net.URI;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link OverrideAuthorityNameResolverFactory}. */
@RunWith(JUnit4.class)
public class OverrideAuthorityNameResolverTest {
  private static final NameResolver.Helper HELPER = new NameResolver.Helper() {
      @Override
      public int getDefaultPort() {
        throw new UnsupportedOperationException("Should not be called");
      }

      @Override
      public ProxyDetector getProxyDetector() {
        throw new UnsupportedOperationException("Should not be called");
      }
    };

  @Test
  public void overridesAuthority() {
    NameResolver nameResolverMock = mock(NameResolver.class);
    NameResolver.Factory wrappedFactory = mock(NameResolver.Factory.class);
    when(wrappedFactory.newNameResolver(any(URI.class), any(NameResolver.Helper.class)))
        .thenReturn(nameResolverMock);
    String override = "override:5678";
    NameResolver.Factory factory =
        new OverrideAuthorityNameResolverFactory(wrappedFactory, override);
    NameResolver nameResolver = factory.newNameResolver(URI.create("dns:///localhost:443"), HELPER);
    assertNotNull(nameResolver);
    assertEquals(override, nameResolver.getServiceAuthority());
  }

  @Test
  public void wontWrapNull() {
    NameResolver.Factory wrappedFactory = mock(NameResolver.Factory.class);
    when(wrappedFactory.newNameResolver(any(URI.class), any(NameResolver.Helper.class)))
        .thenReturn(null);
    NameResolver.Factory factory =
        new OverrideAuthorityNameResolverFactory(wrappedFactory, "override:5678");
    assertEquals(null,
        factory.newNameResolver(URI.create("dns:///localhost:443"), HELPER));
  }

  @Test
  public void forwardsNonOverridenCalls() {
    NameResolver.Factory wrappedFactory = mock(NameResolver.Factory.class);
    NameResolver mockResolver = mock(NameResolver.class);
    when(wrappedFactory.newNameResolver(any(URI.class), any(NameResolver.Helper.class)))
        .thenReturn(mockResolver);
    NameResolver.Factory factory =
        new OverrideAuthorityNameResolverFactory(wrappedFactory, "override:5678");
    NameResolver overrideResolver =
        factory.newNameResolver(URI.create("dns:///localhost:443"), HELPER);
    assertNotNull(overrideResolver);
    NameResolver.Observer listener = mock(NameResolver.Observer.class);

    overrideResolver.start(listener);
    verify(mockResolver).start(listener);

    overrideResolver.shutdown();
    verify(mockResolver).shutdown();

    overrideResolver.refresh();
    verify(mockResolver).refresh();
  }
}
