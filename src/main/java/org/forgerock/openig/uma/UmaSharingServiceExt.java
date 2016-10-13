/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 Charan Mann
 * Portions Copyrighted 2016 ForgeRock AS
 *
 * openig-uma-ext: Created by Charan Mann on 10/7/16 , 12:55 PM.
 */

package org.forgerock.openig.uma;

import org.forgerock.http.Handler;
import org.forgerock.http.MutableUri;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.protocol.Responses;
import org.forgerock.http.protocol.Status;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.opendj.ldap.EntryNotFoundException;
import org.forgerock.opendj.ldap.LdapException;
import org.forgerock.openig.heap.GenericHeaplet;
import org.forgerock.openig.heap.HeapException;
import org.forgerock.openig.http.EndpointRegistry;
import org.forgerock.services.context.Context;
import org.forgerock.util.Function;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static java.lang.String.format;
import static org.forgerock.json.JsonValue.*;
import static org.forgerock.json.JsonValueFunctions.uri;
import static org.forgerock.json.resource.Resources.newCollection;
import static org.forgerock.json.resource.http.CrestHttp.newHttpHandler;
import static org.forgerock.openig.util.JsonValues.evaluated;
import static org.forgerock.openig.util.JsonValues.requiredHeapObject;
import static org.forgerock.util.promise.Promises.newExceptionPromise;

/**
 * Extension of {@code UmaSharingService}. Adds: Support for realm
 */
public class UmaSharingServiceExt  {

    private final Handler protectionApiHandler;
    private final URI authorizationServer;
    private final URI introspectionEndpoint;
    private final URI ticketEndpoint;
    private final URI resourceSetEndpoint;
    private final String clientId;
    private final String clientSecret;
    private LDAPManager ldapManager;


    /**
     * Constructs an UmaSharingService bound to the given {@code authorizationServer} and dedicated to protect resource
     * sets described by the given {@code templates}.
     *
     * @param protectionApiHandler used to call the resource set endpoint
     * @param authorizationServer  Bound UMA Authorization Server
     * @param clientId             OAuth 2.0 Client identifier
     * @param clientSecret         OAuth 2.0 Client secret
     * @throws URISyntaxException when the authorization server URI cannot be "normalized" (trailing '/' append if required)
     */
    public UmaSharingServiceExt(final Handler protectionApiHandler,
                                String realm,
                                final URI authorizationServer,
                                final String clientId,
                                final String clientSecret,
                                final LDAPManager ldapManager)
            throws URISyntaxException {
        this.protectionApiHandler = protectionApiHandler;
        this.authorizationServer = appendTrailingSlash(authorizationServer);

        this.introspectionEndpoint = authorizationServer.resolve("oauth2" + realm + "/introspect");
        this.ticketEndpoint = authorizationServer.resolve("uma" + realm + "/permission_request");
        this.resourceSetEndpoint = authorizationServer.resolve("oauth2" + realm + "/resource_set");
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.ldapManager = ldapManager;
    }

    /**
     * Append a trailing {@literal /} if missing.
     *
     * @param uri URI to be "normalized"
     * @return a URI with a trailing {@literal /}
     * @throws URISyntaxException should never happen
     */
    private static URI appendTrailingSlash(final URI uri) throws URISyntaxException {
        if (!uri.getPath().endsWith("/")) {
            MutableUri mutable = new MutableUri(uri);
            mutable.setRawPath(uri.getRawPath().concat("/"));
            return mutable.asURI();
        }
        return uri;
    }

    /**
     * Creates a Share that will be used to protect the given {@code resourcePath}.
     *
     * @param context       Context chain used to keep a relationship between requests (tracking)
     * @param createRequest CreateRequest
     * @return the created {@link Share} asynchronously
     * @see <a href="https://docs.kantarainitiative.org/uma/draft-oauth-resource-reg.html#rfc.section.2">Resource Set
     * Registration</a>
     */
    public Promise<ShareExt, UmaException> createShare(final Context context,
                                                       final CreateRequest createRequest) {

        final String resourcePath = createRequest.getContent().get("path").asString();
        final String pat = createRequest.getContent().get("pat").asString();
        String name = createRequest.getContent().get("name").asString();
        String type = createRequest.getContent().get("type").asString();
        Set<Object> scopes = createRequest.getContent().get("scopes").asSet();

        if (isShared(resourcePath)) {
            // We do not accept re-sharing or post-creation resource_set configuration
            return newExceptionPromise(new UmaException(format("Resource %s is already shared", resourcePath)));
        }

        // Need to find which ShareTemplate to use
        //final ShareTemplate matching = findShareTemplate(resourcePath);

        //if (matching == null) {
        //  return newExceptionPromise(new UmaException(format("Can't find a template for resource %s", resourcePath)));
        //}

        return createResourceSet(context, pat, resourceSet(name, scopes, type))
                .then(new Function<Response, ShareExt, UmaException>() {
                    @Override
                    public ShareExt apply(final Response response) throws UmaException {
                        if (response.getStatus() == Status.CREATED) {
                            try {
                                JsonValue value = json(response.getEntity().getJson());
                                ShareExt share = new ShareExt(value.get("_id").asString(), pat, resourcePath, value.get("user_access_policy_uri").asString());
                                ldapManager.addShare(share);
                                return share;
                            } catch (IOException e) {
                                throw new UmaException("Can't read the CREATE resource_set response", e);
                            }
                        }
                        throw new UmaException("Cannot register resource_set in AS: " + response.getEntity());
                    }
                }, Responses.<ShareExt, UmaException>noopExceptionFunction());
    }

    private boolean isShared(final String resourcePath) {
        try {
            return (ldapManager.getShare(resourcePath) != null);
        } catch (EntryNotFoundException e) {
            return false;
        } catch (LdapException e) {
            e.printStackTrace();
            //TODO handle this
        }
        return false;
    }

    private Promise<Response, NeverThrowsException> createResourceSet(final Context context,
                                                                      final String pat,
                                                                      final JsonValue data) {
        Request request = new Request();
        request.setMethod("POST");
        request.setUri(resourceSetEndpoint);
        request.getHeaders().put("Authorization", format("Bearer %s", pat));
        request.getHeaders().put("Accept", "application/json");

        request.setEntity(data.asMap());

        return protectionApiHandler.handle(context, request);
    }

    private JsonValue resourceSet(final String name, final Set<Object> scopes, final String type) {
        return json(object(field("name", name),
                field("scopes", scopes), field("type", type)));
    }

    /**
     * Find a {@link ShareExt}.
     *
     * @param request the incoming requesting party request
     * @return a {@link ShareExt} to be used to protect the resource access
     * @throws UmaException when no {@link ShareExt} can handle the request.
     */
    public ShareExt findShare(Request request) throws UmaException {

        // Need to find which Share to use
        String resourcePath = request.getUri().getPath();

        try {
            return ldapManager.getShare(resourcePath);
        } catch (EntryNotFoundException e) {
            throw new UmaException(format("Can't find any shared resource for %s", resourcePath));
        } catch (LdapException e) {
            e.printStackTrace();
            //TODO handle this
        }

        throw new UmaException(format("Can't find any shared resource for %s", resourcePath));
    }

    /**
     * Removes the previously created Share from the registered shares. In effect, the resources is no more
     * shared/protected
     *
     * @param shareId share identifier
     * @return the removed Share instance if found, {@code null} otherwise.
     */
    public ShareExt removeShare(String shareId) {
        //return shares.remove(shareId);
        return null;
    }


    /**
     * Returns the UMA authorization server base Uri.
     *
     * @return the UMA authorization server base Uri.
     */
    public URI getAuthorizationServer() {
        return authorizationServer;
    }

    /**
     * Returns the UMA Permission Request endpoint Uri.
     *
     * @return the UMA Permission Request endpoint Uri.
     */
    public URI getTicketEndpoint() {
        return ticketEndpoint;
    }

    /**
     * Returns the OAuth 2.0 Introspection endpoint Uri.
     *
     * @return the OAuth 2.0 Introspection endpoint Uri.
     */
    public URI getIntrospectionEndpoint() {
        return introspectionEndpoint;
    }

    /**
     * Returns the {@link ShareExt} with the given {@code id}.
     *
     * @param id Share identifier
     * @return the {@link ShareExt} with the given {@code id} (or {@code null} if none was found).
     */
    public ShareExt getShare(final String id) {
        //return shares.get(id);
        return null;
    }

    /**
     * Returns a copy of the list of currently managed shares.
     * @return a copy of the list of currently managed shares.
     */
    public Set<ShareExt> listShares() {
        return Collections.EMPTY_SET;
    }

    /**
     * Returns the client identifier used to identify this RS as an OAuth 2.0 client.
     *
     * @return the client identifier used to identify this RS as an OAuth 2.0 client.
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * Returns the client secret.
     *
     * @return the client secret.
     */
    public String getClientSecret() {
        return clientSecret;
    }

    /**
     * Creates and initializes an UMA service in a heap environment.
     */
    public static class Heaplet extends GenericHeaplet {

        private static String startsWithSlash(final String realm) {
            String nonNullRealm = realm != null ? realm : "/";
            return nonNullRealm.startsWith("/") ? nonNullRealm : "/" + nonNullRealm;
        }

        @Override
        public Object create() throws HeapException {
            Handler handler = config.get("protectionApiHandler").required().as(requiredHeapObject(heap, Handler.class));
            URI uri = config.get("authorizationServerUri").as(evaluated()).required().as(uri());
            String realm = startsWithSlash(config.get("realm").defaultTo("/").asString());
            String clientId = config.get("clientId").as(evaluated()).required().asString();
            String clientSecret = config.get("clientSecret").as(evaluated()).required().asString();
            LDAPManager ldapManager = new LDAPManager("192.168.56.122", 3389, "cn=Directory Manager", "cangetindj", "dc=openig,dc=forgerock,dc=org");
            try {
                UmaSharingServiceExt service = new UmaSharingServiceExt(handler, realm,
                        uri,
                        clientId,
                        clientSecret,
                        ldapManager);
                // register admin endpoint
                Handler httpHandler = newHttpHandler(newCollection(new ShareCollectionProviderExt(service)));
                EndpointRegistry.Registration share = endpointRegistry().register("share", httpHandler);
                logger.info(format("UMA Share endpoint available at '%s'", share.getPath()));

                return service;
            } catch (URISyntaxException e) {
                throw new HeapException("Cannot build UmaSharingService", e);
            }
        }

    }

}

