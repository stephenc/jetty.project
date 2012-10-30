//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;

public class RedirectProtocolHandler extends Response.Listener.Empty implements ProtocolHandler
{
    private static final String ATTRIBUTE = RedirectProtocolHandler.class.getName() + ".redirects";

    private final HttpClient client;
    private final ResponseNotifier notifier;

    public RedirectProtocolHandler(HttpClient client)
    {
        this.client = client;
        this.notifier = new ResponseNotifier(client);
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        switch (response.getStatus())
        {
            case 301:
            case 302:
            case 303:
            case 307:
                return request.isFollowRedirects();
        }
        return false;
    }

    @Override
    public Response.Listener getResponseListener()
    {
        return this;
    }

    @Override
    public void onComplete(Result result)
    {
        if (!result.isFailed())
        {
            Request request = result.getRequest();
            Response response = result.getResponse();
            String location = response.getHeaders().get("location");
            int status = response.getStatus();
            switch (status)
            {
                case 301:
                {
                    if (request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.HEAD)
                        redirect(result, request.getMethod(), location);
                    else
                        fail(result, new HttpResponseException("HTTP protocol violation: received 301 for non GET or HEAD request", response));
                    break;
                }
                case 302:
                case 303:
                {
                    // Redirect must be done using GET
                    redirect(result, HttpMethod.GET, location);
                    break;
                }
                case 307:
                {
                    // Keep same method
                    redirect(result, request.getMethod(), location);
                    break;
                }
                default:
                {
                    fail(result, new HttpResponseException("Unhandled HTTP status code " + status, response));
                    break;
                }
            }
        }
        else
        {
            fail(result, result.getFailure());
        }
    }

    private void redirect(Result result, HttpMethod method, String location)
    {
        final Request request = result.getRequest();
        HttpConversation conversation = client.getConversation(request.getConversationID(), false);
        Integer redirects = (Integer)conversation.getAttribute(ATTRIBUTE);
        if (redirects == null)
            redirects = 0;

        if (redirects < client.getMaxRedirects())
        {
            ++redirects;
            conversation.setAttribute(ATTRIBUTE, redirects);

            Request redirect = client.newRequest(request.getConversationID(), location);

            // Use given method
            redirect.method(method);

            redirect.version(request.getVersion());

            // Copy headers
            for (HttpFields.Field header : request.getHeaders())
                redirect.header(header.getName(), header.getValue());

            // Copy content
            redirect.content(request.getContent());

            redirect.listener(new Request.Listener.Empty()
            {
                @Override
                public void onBegin(Request redirect)
                {
                    if (request.aborted())
                        redirect.abort(null);
                }
            });

            redirect.send(new Response.Listener.Empty());
        }
        else
        {
            fail(result, new HttpResponseException("Max redirects exceeded " + redirects, result.getResponse()));
        }
    }

    private void fail(Result result, Throwable failure)
    {
        Request request = result.getRequest();
        Response response = result.getResponse();
        HttpConversation conversation = client.getConversation(request.getConversationID(), false);
        Response.Listener listener = conversation.getExchanges().peekFirst().getResponseListener();
        // TODO: should we replay all events, or just the failure ?
        notifier.notifyFailure(listener, response, failure);
        notifier.notifyComplete(listener, new Result(request, response, failure));
    }
}
