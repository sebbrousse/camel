/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.processor.binding;

import java.util.Locale;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.processor.MarshalProcessor;
import org.apache.camel.processor.UnmarshalProcessor;
import org.apache.camel.spi.DataFormat;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.support.SynchronizationAdapter;
import org.apache.camel.util.AsyncProcessorHelper;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.MessageHelper;
import org.apache.camel.util.ObjectHelper;

/**
 * A {@link org.apache.camel.Processor} that binds the REST DSL incoming and outgoing messages
 * from sources of json or xml to Java Objects.
 * <p/>
 * The binding uses {@link org.apache.camel.spi.DataFormat} for the actual work to transform
 * from xml/json to Java Objects and reverse again.
 */
public class RestBindingProcessor extends ServiceSupport implements AsyncProcessor {

    // TODO: consumes/produces can be a list of media types, and prioritized 1st to last. (eg the q=weight option)
    // TODO: use content-type from produces/consumes if possible to set as Content-Type if missing

    private final AsyncProcessor jsonUnmarshal;
    private final AsyncProcessor xmlUnmarshal;
    private final AsyncProcessor jsonMarshal;
    private final AsyncProcessor xmlMarshal;
    private final String consumes;
    private final String produces;
    private final String bindingMode;

    public RestBindingProcessor(DataFormat jsonDataFormat, DataFormat xmlDataFormat,
                                DataFormat outJsonDataFormat, DataFormat outXmlDataFormat,
                                String consumes, String produces, String bindingMode) {

        if (jsonDataFormat != null) {
            this.jsonUnmarshal = new UnmarshalProcessor(jsonDataFormat);
        } else {
            this.jsonUnmarshal = null;
        }
        if (outJsonDataFormat != null) {
            this.jsonMarshal = new MarshalProcessor(outJsonDataFormat);
        } else if (jsonDataFormat != null) {
            this.jsonMarshal = new MarshalProcessor(jsonDataFormat);
        } else {
            this.jsonMarshal = null;
        }

        if (xmlDataFormat != null) {
            this.xmlUnmarshal = new UnmarshalProcessor(xmlDataFormat);
        } else {
            this.xmlUnmarshal = null;
        }
        if (outXmlDataFormat != null) {
            this.xmlMarshal = new MarshalProcessor(outXmlDataFormat);
        } else if (xmlDataFormat != null) {
            this.xmlMarshal = new MarshalProcessor(xmlDataFormat);
        } else {
            this.xmlMarshal = null;
        }

        this.consumes = consumes;
        this.produces = produces;
        this.bindingMode = bindingMode;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        AsyncProcessorHelper.process(this, exchange);
    }

    @Override
    public boolean process(Exchange exchange, final AsyncCallback callback) {
        if (bindingMode == null || "off".equals(bindingMode)) {
            // binding is off
            callback.done(true);
            return true;
        }

        // is there any unmarshaller at all
        if (jsonUnmarshal == null && xmlUnmarshal == null) {
            callback.done(true);
            return true;
        }

        boolean isXml = false;
        boolean isJson = false;

        // content type takes precedence, over consumes
        String contentType = ExchangeHelper.getContentType(exchange);
        if (contentType != null) {
            isXml = contentType.toLowerCase(Locale.US).contains("xml");
            isJson = contentType.toLowerCase(Locale.US).contains("json");
        }
        // if content type could not tell us if it was json or xml, then fallback to if the binding was configured with
        // that information in the consumes
        if (!isXml && !isJson) {
            isXml = consumes != null && consumes.toLowerCase(Locale.US).contains("xml");
            isJson = consumes != null && consumes.toLowerCase(Locale.US).contains("json");
        }

        // only allow xml/json if the binding mode allows that
        isXml &= bindingMode.equals("auto") || bindingMode.contains("xml");
        isJson &= bindingMode.equals("auto") || bindingMode.contains("json");

        // if we do not yet know if its xml or json, then use the binding mode to know the mode
        if (!isJson && !isXml) {
            isXml = bindingMode.equals("auto") || bindingMode.contains("xml");
            isJson = bindingMode.equals("auto") || bindingMode.contains("json");
        }

        String body = null;

        if (exchange.getIn().getBody() != null) {

           // okay we have a binding mode, so need to check for empty body as that can cause the marshaller to fail
            // as they assume a non-empty body
            if (isXml || isJson) {
                // we have binding enabled, so we need to know if there body is empty or not\
                // so force reading the body as a String which we can work with
                body = MessageHelper.extractBodyAsString(exchange.getIn());
                if (body != null) {
                    exchange.getIn().setBody(body);

                    if (isXml && isJson) {
                        // we have still not determined between xml or json, so check the body if its xml based or not
                        isXml = body.startsWith("<");
                        isJson = !isXml;
                    }
                }
            }
        }

        if (isXml && xmlUnmarshal != null) {
            // add reverse operation
            exchange.addOnCompletion(new RestBindingMarshalOnCompletion(exchange.getFromRouteId(), jsonMarshal, xmlMarshal, true));
            if (ObjectHelper.isNotEmpty(body)) {
                return xmlUnmarshal.process(exchange, callback);
            } else {
                callback.done(true);
                return true;
            }
        } else if (isJson && jsonUnmarshal != null) {
            // add reverse operation
            exchange.addOnCompletion(new RestBindingMarshalOnCompletion(exchange.getFromRouteId(), jsonMarshal, xmlMarshal, false));
            if (ObjectHelper.isNotEmpty(body)) {
                return jsonUnmarshal.process(exchange, callback);
            } else {
                callback.done(true);
                return true;
            }
        }

        // we could not bind
        if (bindingMode.equals("auto")) {
            // okay for auto we do not mind if we could not bind
            exchange.addOnCompletion(new RestBindingMarshalOnCompletion(exchange.getFromRouteId(), jsonMarshal, xmlMarshal, false));
            callback.done(true);
            return true;
        } else {
            if (bindingMode.contains("xml")) {
                exchange.setException(new BindingException("Cannot bind to xml as message body is not xml compatible", exchange));
            } else {
                exchange.setException(new BindingException("Cannot bind to json as message body is not json compatible", exchange));
            }
            callback.done(true);
            return true;
        }
    }

    @Override
    public String toString() {
        return "RestBindingProcessor";
    }

    @Override
    protected void doStart() throws Exception {
        // noop
    }

    @Override
    protected void doStop() throws Exception {
        // noop
    }

    /**
     * An {@link org.apache.camel.spi.Synchronization} that does the reverse operation
     * of marshalling from POJO to json/xml
     */
    private final class RestBindingMarshalOnCompletion extends SynchronizationAdapter {

        private final AsyncProcessor jsonMarshal;
        private final AsyncProcessor xmlMarshal;
        private final String routeId;
        private boolean wasXml;

        private RestBindingMarshalOnCompletion(String routeId, AsyncProcessor jsonMarshal, AsyncProcessor xmlMarshal, boolean wasXml) {
            this.routeId = routeId;
            this.jsonMarshal = jsonMarshal;
            this.xmlMarshal = xmlMarshal;
            this.wasXml = wasXml;
        }

        @Override
        public void onAfterRoute(Route route, Exchange exchange) {
            // we use the onAfterRoute callback, to ensure the data has been marshalled before
            // the consumer writes the response back

            // only trigger when it was the 1st route that was done
            if (!routeId.equals(route.getId())) {
                return;
            }

            // only marshal if there was no exception
            if (exchange.getException() != null) {
                return;
            }

            if (bindingMode == null || "off".equals(bindingMode)) {
                // binding is off
                return;
            }

            // is there any marshaller at all
            if (jsonMarshal == null && xmlMarshal == null) {
                return;
            }

            // is the body empty
            if ((exchange.hasOut() && exchange.getOut().getBody() == null) || (!exchange.hasOut() && exchange.getIn().getBody() == null)) {
                return;
            }

            boolean isXml = false;
            boolean isJson = false;

            String contentType = ExchangeHelper.getContentType(exchange);
            if (contentType != null) {
                isXml = contentType.toLowerCase(Locale.US).contains("xml");
                isJson = contentType.toLowerCase(Locale.US).contains("json");
            }
            // if content type could not tell us if it was json or xml, then fallback to if the binding was configured with
            // that information in the consumes
            if (!isXml && !isJson) {
                isXml = produces != null && produces.toLowerCase(Locale.US).contains("xml");
                isJson = produces != null && produces.toLowerCase(Locale.US).contains("json");
            }

            // only allow xml/json if the binding mode allows that
            isXml &= bindingMode.equals("auto") || bindingMode.contains("xml");
            isJson &= bindingMode.equals("auto") || bindingMode.contains("json");

            // if we do not yet know if its xml or json, then use the binding mode to know the mode
            if (!isJson && !isXml) {
                isXml = bindingMode.equals("auto") || bindingMode.contains("xml");
                isJson = bindingMode.equals("auto") || bindingMode.contains("json");
            }

            // in case we have not yet been able to determine if xml or json, then use the same as in the unmarshaller
            if (isXml && isJson) {
                isXml = wasXml;
                isJson = !wasXml;
            }

            // need to prepare exchange first
            ExchangeHelper.prepareOutToIn(exchange);

            try {
                if (isXml && xmlMarshal != null) {
                    // make sure there is a content-type with xml
                    String type = ExchangeHelper.getContentType(exchange);
                    if (type == null) {
                        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/xml");
                    }
                    xmlMarshal.process(exchange);
                } else if (isJson && jsonMarshal != null) {
                    // make sure there is a content-type with json
                    String type = ExchangeHelper.getContentType(exchange);
                    if (type == null) {
                        exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
                    }
                    jsonMarshal.process(exchange);
                } else {
                    // we could not bind
                    if (bindingMode.equals("auto")) {
                        // okay for auto we do not mind if we could not bind
                    } else {
                        if (bindingMode.contains("xml")) {
                            exchange.setException(new BindingException("Cannot bind to xml as message body is not xml compatible", exchange));
                        } else {
                            exchange.setException(new BindingException("Cannot bind to json as message body is not json compatible", exchange));
                        }
                    }
                }
            } catch (Throwable e) {
                exchange.setException(e);
            }
        }
    }

}
