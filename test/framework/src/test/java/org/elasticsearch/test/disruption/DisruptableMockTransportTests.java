/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.disruption;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.coordination.DeterministicTaskQueue;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.disruption.DisruptableMockTransport.ConnectionStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportResponse.Empty;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.elasticsearch.transport.TransportService.NOOP_TRANSPORT_INTERCEPTOR;
import static org.hamcrest.Matchers.containsString;

public class DisruptableMockTransportTests extends ESTestCase {

    DiscoveryNode node1;
    DiscoveryNode node2;

    DisruptableMockTransport transport1;
    DisruptableMockTransport transport2;

    TransportService service1;
    TransportService service2;

    DeterministicTaskQueue deterministicTaskQueue;

    Set<Tuple<DiscoveryNode, DiscoveryNode>> disconnectedLinks;
    Set<Tuple<DiscoveryNode, DiscoveryNode>> blackholedLinks;

    ConnectionStatus getConnectionStatus(DiscoveryNode sender, DiscoveryNode destination) {
        Tuple<DiscoveryNode, DiscoveryNode> link = Tuple.tuple(sender, destination);
        if (disconnectedLinks.contains(link)) {
            assert blackholedLinks.contains(link) == false;
            return ConnectionStatus.DISCONNECTED;
        }
        if (blackholedLinks.contains(link)) {
            return ConnectionStatus.BLACK_HOLE;
        }
        return ConnectionStatus.CONNECTED;
    }

    @Before
    public void initTransports() {
        node1 = new DiscoveryNode("node1", buildNewFakeTransportAddress(), Version.CURRENT);
        node2 = new DiscoveryNode("node2", buildNewFakeTransportAddress(), Version.CURRENT);

        disconnectedLinks = new HashSet<>();
        blackholedLinks = new HashSet<>();

        List<DisruptableMockTransport> transports = new ArrayList<>();

        deterministicTaskQueue = new DeterministicTaskQueue(
            Settings.builder().put(Node.NODE_NAME_SETTING.getKey(), "dummy").build(), random());

        transport1 = new DisruptableMockTransport(node1, logger) {
            @Override
            protected ConnectionStatus getConnectionStatus(DiscoveryNode destination) {
                return DisruptableMockTransportTests.this.getConnectionStatus(getLocalNode(), destination);
            }

            @Override
            protected Optional<DisruptableMockTransport> getDisruptableMockTransport(TransportAddress address) {
                return transports.stream().filter(t -> t.getLocalNode().getAddress().equals(address)).findAny();
            }

            @Override
            protected void execute(Runnable runnable) {
                deterministicTaskQueue.scheduleNow(runnable);
            }
        };

        transport2 = new DisruptableMockTransport(node2, logger) {
            @Override
            protected ConnectionStatus getConnectionStatus(DiscoveryNode destination) {
                return DisruptableMockTransportTests.this.getConnectionStatus(getLocalNode(), destination);
            }

            @Override
            protected Optional<DisruptableMockTransport> getDisruptableMockTransport(TransportAddress address) {
                return transports.stream().filter(t -> t.getLocalNode().getAddress().equals(address)).findAny();
            }

            @Override
            protected void execute(Runnable runnable) {
                deterministicTaskQueue.scheduleNow(runnable);
            }
        };

        transports.add(transport1);
        transports.add(transport2);

        service1 = transport1.createTransportService(Settings.EMPTY, deterministicTaskQueue.getThreadPool(),
            NOOP_TRANSPORT_INTERCEPTOR, a -> node1, null, Collections.emptySet());
        service2 = transport2.createTransportService(Settings.EMPTY, deterministicTaskQueue.getThreadPool(),
            NOOP_TRANSPORT_INTERCEPTOR, a -> node2, null, Collections.emptySet());

        service1.start();
        service2.start();

        service1.connectToNode(node2);
        service2.connectToNode(node1);
    }


    private TransportRequestHandler<TransportRequest.Empty> requestHandlerShouldNotBeCalled() {
        return (request, channel, task) -> {
            throw new AssertionError("should not be called");
        };
    }

    private TransportRequestHandler<TransportRequest.Empty> requestHandlerRepliesNormally() {
        return (request, channel, task) -> {
            logger.debug("got a dummy request, replying normally...");
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        };
    }

    private TransportRequestHandler<TransportRequest.Empty> requestHandlerRepliesExceptionally(Exception e) {
        return (request, channel, task) -> {
            logger.debug("got a dummy request, replying exceptionally...");
            channel.sendResponse(e);
        };
    }

    private TransportRequestHandler<TransportRequest.Empty> requestHandlerCaptures(Consumer<TransportChannel> channelConsumer) {
        return (request, channel, task) -> {
            logger.debug("got a dummy request...");
            channelConsumer.accept(channel);
        };
    }

    private TransportResponseHandler<TransportResponse> responseHandlerShouldNotBeCalled() {
        return new TransportResponseHandler<TransportResponse>() {
            @Override
            public TransportResponse read(StreamInput in) {
                throw new AssertionError("should not be called");
            }

            @Override
            public void handleResponse(TransportResponse response) {
                throw new AssertionError("should not be called");
            }

            @Override
            public void handleException(TransportException exp) {
                throw new AssertionError("should not be called");
            }

            @Override
            public String executor() {
                return ThreadPool.Names.SAME;
            }
        };
    }

    private TransportResponseHandler<TransportResponse> responseHandlerShouldBeCalledNormally(Runnable onCalled) {
        return new TransportResponseHandler<TransportResponse>() {
            @Override
            public TransportResponse read(StreamInput in) {
                return Empty.INSTANCE;
            }

            @Override
            public void handleResponse(TransportResponse response) {
                onCalled.run();
            }

            @Override
            public void handleException(TransportException exp) {
                throw new AssertionError("should not be called");
            }

            @Override
            public String executor() {
                return ThreadPool.Names.SAME;
            }
        };
    }

    private TransportResponseHandler<TransportResponse> responseHandlerShouldBeCalledExceptionally(Consumer<TransportException> onCalled) {
        return new TransportResponseHandler<TransportResponse>() {
            @Override
            public TransportResponse read(StreamInput in) {
                throw new AssertionError("should not be called");
            }

            @Override
            public void handleResponse(TransportResponse response) {
                throw new AssertionError("should not be called");
            }

            @Override
            public void handleException(TransportException exp) {
                onCalled.accept(exp);
            }

            @Override
            public String executor() {
                return ThreadPool.Names.SAME;
            }
        };
    }

    private void registerRequestHandler(TransportService transportService, TransportRequestHandler<TransportRequest.Empty> handler) {
        transportService.registerRequestHandler("internal:dummy", () -> TransportRequest.Empty.INSTANCE, ThreadPool.Names.GENERIC, handler);
    }

    private void send(TransportService transportService, DiscoveryNode destinationNode,
                      TransportResponseHandler<TransportResponse> responseHandler) {
        transportService.sendRequest(destinationNode, "internal:dummy", TransportRequest.Empty.INSTANCE, responseHandler);
    }

    public void testSuccessfulResponse() {
        registerRequestHandler(service1, requestHandlerShouldNotBeCalled());
        registerRequestHandler(service2, requestHandlerRepliesNormally());
        AtomicBoolean responseHandlerCalled = new AtomicBoolean();
        send(service1, node2, responseHandlerShouldBeCalledNormally(() -> responseHandlerCalled.set(true)));
        deterministicTaskQueue.runAllRunnableTasks();
        assertTrue(responseHandlerCalled.get());
    }

    public void testExceptionalResponse() {
        registerRequestHandler(service1, requestHandlerShouldNotBeCalled());
        Exception e = new Exception("dummy exception");
        registerRequestHandler(service2, requestHandlerRepliesExceptionally(e));
        AtomicReference<TransportException> responseHandlerException = new AtomicReference<>();
        send(service1, node2, responseHandlerShouldBeCalledExceptionally(responseHandlerException::set));
        deterministicTaskQueue.runAllRunnableTasks();
        assertNotNull(responseHandlerException.get());
        assertNotNull(responseHandlerException.get().getCause());
        assertThat(responseHandlerException.get().getCause().getMessage(), containsString("dummy exception"));
    }

    public void testDisconnectedOnRequest() {
        registerRequestHandler(service1, requestHandlerShouldNotBeCalled());
        registerRequestHandler(service2, requestHandlerShouldNotBeCalled());
        disconnectedLinks.add(Tuple.tuple(node1, node2));
        AtomicReference<TransportException> responseHandlerException = new AtomicReference<>();
        send(service1, node2, responseHandlerShouldBeCalledExceptionally(responseHandlerException::set));
        deterministicTaskQueue.runAllRunnableTasks();
        assertNotNull(responseHandlerException.get());
        assertThat(responseHandlerException.get().getMessage(), containsString("disconnected"));
    }

    public void testUnavailableOnRequest() {
        registerRequestHandler(service1, requestHandlerShouldNotBeCalled());
        registerRequestHandler(service2, requestHandlerShouldNotBeCalled());
        blackholedLinks.add(Tuple.tuple(node1, node2));
        send(service1, node2, responseHandlerShouldNotBeCalled());
        deterministicTaskQueue.runAllRunnableTasks();
    }

    public void testDisconnectedOnSuccessfulResponse() throws IOException {
        registerRequestHandler(service1, requestHandlerShouldNotBeCalled());
        AtomicReference<TransportChannel> responseHandlerChannel = new AtomicReference<>();
        registerRequestHandler(service2, requestHandlerCaptures(responseHandlerChannel::set));

        AtomicReference<TransportException> responseHandlerException = new AtomicReference<>();
        send(service1, node2, responseHandlerShouldNotBeCalled());
        deterministicTaskQueue.runAllRunnableTasks();
        assertNull(responseHandlerException.get());
        assertNotNull(responseHandlerChannel.get());

        disconnectedLinks.add(Tuple.tuple(node2, node1));
        responseHandlerChannel.get().sendResponse(TransportResponse.Empty.INSTANCE);
        deterministicTaskQueue.runAllRunnableTasks();
    }

    public void testDisconnectedOnExceptionalResponse() throws IOException {
        registerRequestHandler(service1, requestHandlerShouldNotBeCalled());
        AtomicReference<TransportChannel> responseHandlerChannel = new AtomicReference<>();
        registerRequestHandler(service2, requestHandlerCaptures(responseHandlerChannel::set));

        AtomicReference<TransportException> responseHandlerException = new AtomicReference<>();
        send(service1, node2, responseHandlerShouldNotBeCalled());
        deterministicTaskQueue.runAllRunnableTasks();
        assertNull(responseHandlerException.get());
        assertNotNull(responseHandlerChannel.get());

        disconnectedLinks.add(Tuple.tuple(node2, node1));
        responseHandlerChannel.get().sendResponse(new Exception());
        deterministicTaskQueue.runAllRunnableTasks();
    }

    public void testUnavailableOnSuccessfulResponse() throws IOException {
        registerRequestHandler(service1, requestHandlerShouldNotBeCalled());
        AtomicReference<TransportChannel> responseHandlerChannel = new AtomicReference<>();
        registerRequestHandler(service2, requestHandlerCaptures(responseHandlerChannel::set));

        AtomicReference<TransportException> responseHandlerException = new AtomicReference<>();
        send(service1, node2, responseHandlerShouldNotBeCalled());
        deterministicTaskQueue.runAllRunnableTasks();
        assertNull(responseHandlerException.get());
        assertNotNull(responseHandlerChannel.get());

        blackholedLinks.add(Tuple.tuple(node2, node1));
        responseHandlerChannel.get().sendResponse(TransportResponse.Empty.INSTANCE);
        deterministicTaskQueue.runAllRunnableTasks();
    }

    public void testUnavailableOnExceptionalResponse() throws IOException {
        registerRequestHandler(service1, requestHandlerShouldNotBeCalled());
        AtomicReference<TransportChannel> responseHandlerChannel = new AtomicReference<>();
        registerRequestHandler(service2, requestHandlerCaptures(responseHandlerChannel::set));

        AtomicReference<TransportException> responseHandlerException = new AtomicReference<>();
        send(service1, node2, responseHandlerShouldNotBeCalled());
        deterministicTaskQueue.runAllRunnableTasks();
        assertNull(responseHandlerException.get());
        assertNotNull(responseHandlerChannel.get());

        blackholedLinks.add(Tuple.tuple(node2, node1));
        responseHandlerChannel.get().sendResponse(new Exception());
        deterministicTaskQueue.runAllRunnableTasks();
    }

}
