/*
 * Copyright 2012 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.plugin.netty;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereServlet;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

class NettyAtmosphereHandler extends SimpleChannelUpstreamHandler {
    private static final Logger logger = LoggerFactory.getLogger(NettyAtmosphereHandler.class);
    private final AtmosphereServlet as;
    private final Map<String, String> initParams = new HashMap<String, String>();

    public NettyAtmosphereHandler() {
        super();
        as = new AtmosphereServlet();
        try {
            as.init(new NettyServletConfig(initParams, new NettyServletContext.Builder().build()));
        } catch (ServletException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void messageReceived(final ChannelHandlerContext context,
                                final MessageEvent messageEvent) throws URISyntaxException,
            IOException {
        final HttpRequest request = (HttpRequest) messageEvent.getMessage();

        final String base = getBaseUri(request);
        final URI requestUri = new URI(base.substring(0, base.length() - 1) + request.getUri());

        AtmosphereRequest.Builder requestBuilder = new AtmosphereRequest.Builder();
        AtmosphereRequest r = requestBuilder.requestURI(requestUri.toURL().toString())
                .requestURL(requestUri.toURL().toString())
                .headers(getHeaders(request))
                .method(request.getMethod().getName())
                .contentType(request.getHeaders("Content-Type").get(0))
                .inputStream(new ChannelBufferInputStream(request.getContent()))
                .build();

        AtmosphereResponse.Builder responseBuilder = new AtmosphereResponse.Builder();
        responseBuilder.asyncIOWriter(new NettyWriter(context.getChannel()))
                .atmosphereRequest(r);

        try {
            as.doCometSupport(r, responseBuilder.build());
        } catch (ServletException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        // Close the connection when an exception is raised.
        logger.warn("Unexpected exception from downstream.", e.getCause());
        e.getChannel().close();
    }

    private Map<String, String> getHeaders(final HttpRequest request) {
        final Map<String, String> headers = new HashMap<String, String>();

        for (String name : request.getHeaderNames()) {
            // TODO: Add support for multi header
            headers.put(name, request.getHeaders(name).get(0));
        }

        return headers;
    }

    private String getBaseUri(final HttpRequest request) {
        return "http://" + request.getHeader(HttpHeaders.Names.HOST) + "/";

    }

    private final static class NettyWriter implements AsyncIOWriter {

        private final Channel channel;

        public NettyWriter(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void redirect(String location) throws IOException {
            //To change body of implemented methods use File | Settings | File Templates.
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeError(int errorCode, String message) throws IOException {
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(errorCode));
            channel.write(response);
        }

        @Override
        public void write(String data) throws IOException {
            channel.write(ByteBuffer.wrap(data.getBytes()));
        }

        @Override
        public void write(byte[] data) throws IOException {
            channel.write(ByteBuffer.wrap(data));
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            channel.write(ByteBuffer.wrap(data, offset, length));
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private final static class NettyServletConfig implements ServletConfig {

        private final Map<String, String> initParams;
        private final ServletContext context;

        public NettyServletConfig(Map<String, String> initParams, ServletContext context) {
            this.initParams = initParams;
            this.context = context;
        }

        @Override
        public String getServletName() {
            return "AtmosphereServlet";
        }

        @Override
        public ServletContext getServletContext() {
            return context;
        }

        @Override
        public String getInitParameter(String name) {
            return initParams.get(name);
        }

        @Override
        public Enumeration getInitParameterNames() {
            return Collections.enumeration(initParams.values());
        }
    }

    private final static class NettyServletContext implements ServletContext {

        private final Builder b;

        private NettyServletContext(Builder b) {
            this.b = b;
        }

        public final static class Builder {

            private String contextPath = "";
            private final Map<String, Object> attributes = new HashMap<String, Object>();
            private final Map<String, String> initParams = new HashMap<String, String>();
            private String basePath = "/";

            public Builder putAttribute(String s, Object o) {
                attributes.put(s, o);
                return this;
            }

            public Builder contextPath(String s) {
                this.contextPath = s;
                return this;
            }

            public NettyServletContext build() {
                File f = null;
                try {
                    f = new File(".");
                    f.createNewFile();
                } catch (IOException e) {
                    logger.warn("", e);
                }
                f.deleteOnExit();
                basePath = f.getAbsolutePath() + "/";
                return new NettyServletContext(this);
            }

        }

        @Override
        public String getContextPath() {
            return b.contextPath;
        }

        @Override
        public ServletContext getContext(String uripath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getMajorVersion() {
            return 2;
        }

        @Override
        public int getMinorVersion() {
            return 5;
        }

        @Override
        public String getMimeType(String file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set getResourcePaths(String path) {
            File[] files = new File(b.basePath + path).listFiles();
            Set<String> s = new HashSet<String>();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        Set inner = getResourcePaths(f.getAbsolutePath());
                        s.addAll(inner);
                    } else {
                        s.add(f.getAbsolutePath());
                    }
                }
            }
            return s;
        }

        @Override
        public URL getResource(String path) throws MalformedURLException {
            return URI.create("file://" + b.basePath + path).toURL();
        }

        @Override
        public InputStream getResourceAsStream(String path) {
            try {
                return new FileInputStream(new File(URI.create("file://" + b.basePath + path)));
            } catch (FileNotFoundException e) {
                logger.trace("", e);
            }
            return null;
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequestDispatcher getNamedDispatcher(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Servlet getServlet(String name) throws ServletException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Enumeration getServlets() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Enumeration getServletNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void log(String msg) {
            logger.info(msg);
        }

        @Override
        public void log(Exception exception, String msg) {
            logger.error(msg, exception);
        }

        @Override
        public void log(String message, Throwable throwable) {
            logger.error(message, throwable);
        }

        @Override
        public String getRealPath(String path) {
            try {
                return URI.create("file://" + b.basePath + path).toURL().toString();
            } catch (MalformedURLException e) {
                logger.error("", e);
            }
            return null;
        }

        @Override
        public String getServerInfo() {
            return "Netty-Atmosphere/1.0";
        }

        @Override
        public String getInitParameter(String name) {
            return b.initParams.get(name);
        }

        @Override
        public Enumeration getInitParameterNames() {
            return Collections.enumeration(b.initParams.values());
        }

        @Override
        public Object getAttribute(String name) {
            return b.attributes.get(name);
        }

        @Override
        public Enumeration getAttributeNames() {
            return Collections.enumeration(b.attributes.keySet());
        }

        @Override
        public void setAttribute(String name, Object object) {
            b.attributes.put(name, object);
        }

        @Override
        public void removeAttribute(String name) {
            b.attributes.remove(name);
        }

        @Override
        public String getServletContextName() {
            return "Atmosphere";
        }
    }

}