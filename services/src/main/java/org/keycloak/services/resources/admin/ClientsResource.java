/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.services.resources.admin;


import com.google.common.collect.Maps;
import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.authorization.AuthorizationProvider;
import org.keycloak.authorization.admin.AuthorizationService;
import org.keycloak.events.Errors;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.authorization.store.StoreFactory;
import org.keycloak.common.Profile;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.*;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.authorization.ResourceRepresentation;
import org.keycloak.representations.idm.authorization.ResourceServerRepresentation;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.services.ForbiddenException;
import org.keycloak.services.managers.ClientManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;
import org.keycloak.services.validation.ClientValidator;
import org.keycloak.services.validation.PairwiseClientValidator;
import org.keycloak.services.validation.ValidationMessages;
import org.keycloak.validation.ClientValidationUtil;
import org.keycloak.util.JsonSerialization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.Boolean.TRUE;

/**
 * Base resource class for managing a realm's clients.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 * @resource Clients
 */
public class ClientsResource {
    protected static final Logger logger = Logger.getLogger(ClientsResource.class);
    protected RealmModel realm;
    private AdminPermissionEvaluator auth;
    private AdminEventBuilder adminEvent;

    @Context
    protected KeycloakSession session;

    public ClientsResource(RealmModel realm, AdminPermissionEvaluator auth, AdminEventBuilder adminEvent) {
        this.realm = realm;
        this.auth = auth;
        this.adminEvent = adminEvent.resource(ResourceType.CLIENT);

    }

    /**
     * Get clients belonging to the realm
     * <p>
     * Returns a list of clients belonging to the realm
     *
     * @param clientId     filter by clientId
     * @param viewableOnly filter clients that cannot be viewed in full by admin
     * @param search       whether this is a search query or a getClientById query
     * @param firstResult  the first result
     * @param maxResults   the max results to return
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    public List<ClientRepresentation> getClients(@QueryParam("clientId") String clientId,
                                                 @QueryParam("viewableOnly") @DefaultValue("false") boolean viewableOnly,
                                                 @QueryParam("search") @DefaultValue("false") boolean search,
                                                 @QueryParam("first") Integer firstResult,
                                                 @QueryParam("max") Integer maxResults) {
        if (firstResult == null) {
            firstResult = -1;
        }
        if (maxResults == null) {
            maxResults = -1;
        }

        List<ClientRepresentation> rep = new ArrayList<>();
        boolean canView = auth.clients().canView();
        List<ClientModel> clientModels;

        if (clientId == null || clientId.trim().equals("")) {
            clientModels = canView ? realm.getClients(firstResult, maxResults) : realm.getClients();
            auth.clients().requireList();
        } else {
            clientModels = Collections.emptyList();
            if (search) {
                clientModels = canView ? realm.searchClientByClientId(clientId, firstResult, maxResults) : realm.searchClientByClientId(clientId, -1, -1);
            } else {
                ClientModel client = realm.getClientByClientId(clientId);
                if (client != null) {
                    clientModels = Collections.singletonList(client);
                }
            }
        }

        int idx = 0;

        for (ClientModel clientModel : clientModels) {
            if (!canView) {
                if (rep.size() == maxResults) {
                    return rep;
                }
            }

            ClientRepresentation representation = null;

            if (canView || auth.clients().canView(clientModel)) {
                representation = ModelToRepresentation.toRepresentation(clientModel, session);
                representation.setAccess(auth.clients().getAccess(clientModel));
            } else if (!viewableOnly && auth.clients().canView(clientModel)) {
                representation = new ClientRepresentation();
                representation.setId(clientModel.getId());
                representation.setClientId(clientModel.getClientId());
                representation.setDescription(clientModel.getDescription());
            }

            if (representation != null) {
                if (canView || idx++ >= firstResult) {
                    rep.add(representation);
                }
            }
        }
        return rep;
    }

    private AuthorizationService getAuthorizationService(ClientModel clientModel) {
        return new AuthorizationService(session, clientModel, auth, adminEvent);
    }

    /**
     * Create a new client
     * <p>
     * Client's client_id must be unique!
     *
     * @param rep
     * @return
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createClient(final ClientRepresentation rep) {
        auth.clients().requireManage();

        ValidationMessages validationMessages = new ValidationMessages();
        if (!ClientValidator.validate(rep, validationMessages) || !PairwiseClientValidator.validate(session, rep, validationMessages)) {
            Properties messages = AdminRoot.getMessages(session, realm, auth.adminAuth().getToken().getLocale());
            throw new ErrorResponseException(
                    validationMessages.getStringMessages(),
                    validationMessages.getStringMessages(messages),
                    Response.Status.BAD_REQUEST
            );
        }

        try {
            ClientModel clientModel = ClientManager.createClient(session, realm, rep, true);

            if (TRUE.equals(rep.isServiceAccountsEnabled())) {
                UserModel serviceAccount = session.users().getServiceAccount(clientModel);

                if (serviceAccount == null) {
                    new ClientManager(new RealmManager(session)).enableServiceAccount(clientModel);
                }
            }

            adminEvent.operation(OperationType.CREATE).resourcePath(session.getContext().getUri(), clientModel.getId()).representation(rep).success();

            if (TRUE.equals(rep.getAuthorizationServicesEnabled())) {
                AuthorizationService authorizationService = getAuthorizationService(clientModel);

                authorizationService.enable(true);

                ResourceServerRepresentation authorizationSettings = rep.getAuthorizationSettings();

                if (authorizationSettings != null) {
                    authorizationService.resourceServer().importSettings(authorizationSettings);
                }
            }

            ClientValidationUtil.validate(session, clientModel, true, c -> {
                session.getTransactionManager().setRollbackOnly();
                throw new ErrorResponseException(Errors.INVALID_INPUT, c.getError(), Response.Status.BAD_REQUEST);
            });

            return Response.created(session.getContext().getUri().getAbsolutePathBuilder().path(clientModel.getId()).build()).build();
        } catch (ModelDuplicateException e) {
            return ErrorResponse.exists("Client " + rep.getClientId() + " already exists");
        }
    }

    /**
     * Base path for managing a specific client.
     *
     * @param id id of client (not client-id)
     * @return
     */
    @Path("{id}")
    public ClientResource getClient(final @PathParam("id") String id) {

        ClientModel clientModel = realm.getClientById(id);
        if (clientModel == null) {
            // we do this to make sure somebody can't phish ids
            if (auth.clients().canList()) throw new NotFoundException("Could not find client");
            else throw new ForbiddenException();
        }

        session.getContext().setClient(clientModel);

        ClientResource clientResource = new ClientResource(realm, auth, clientModel, session, adminEvent);
        ResteasyProviderFactory.getInstance().injectProperties(clientResource);
        return clientResource;
    }


    @Path("{id}/clientRoleResourceByUser/{userId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    public Set<ResourceRepresentation> getClientRoleResourceByUser(final @PathParam("id") String id, final @PathParam("userId") String userId) {
        auth.clients().requireView();
        AuthorizationProvider authorizationProvider = session.getProvider(AuthorizationProvider.class);
        StoreFactory storeFactory = authorizationProvider.getStoreFactory();
        ResourceServer resourceServer = storeFactory.getResourceServerStore().findById(id);
        final Set<Resource> resources = getResourceByUser(authorizationProvider, resourceServer, id, userId);
        Map<String, ResourceRepresentation> alls = Maps.newLinkedHashMap();
        Set<ResourceRepresentation> allsResource = new LinkedHashSet<>();
        for (Resource resource : resources) {
            ResourceRepresentation resourceRepresentation = ModelToRepresentation.toRepresentation(resource, resourceServer, authorizationProvider, false);
            resourceRepresentation.setParentId(resource.getParentId());
            allsResource.add(resourceRepresentation);
            alls.put(resource.getId(), resourceRepresentation);
        }
        List<ResourceRepresentation> list = new ArrayList<ResourceRepresentation>(toResource(allsResource, alls));
        //排序
        sort(list);
        return new LinkedHashSet<>(list);
    }

    /**
     * 排序
     *
     * @param list
     */
    private void sort(List<ResourceRepresentation> list) {
        if (list != null && list.size() > 0) {
            Collections.sort(list, new Comparator<ResourceRepresentation>() {
                @Override
                public int compare(ResourceRepresentation arg0, ResourceRepresentation arg1) {
                    return arg0.getSort().compareTo(arg1.getSort());
                }
            });
            for (ResourceRepresentation resourceRepresentation : list) {
                sort(resourceRepresentation.getSubResources());
            }

        }

    }

    /**
     * 转成树结构
     *
     * @param resources
     * @param alls
     * @return
     */
    private Set<ResourceRepresentation> toResource(Set<ResourceRepresentation> resources, Map<String, ResourceRepresentation> alls) {
        if (resources == null || resources.isEmpty()) {
            return new HashSet<>();
        }
        Set<ResourceRepresentation> resourceRepresentations = new LinkedHashSet<>();
        for (ResourceRepresentation resourceRepresentation : resources) {
            if (resourceRepresentation.getParentId() != null) {
                ResourceRepresentation parent = alls.get(resourceRepresentation.getParentId());
                if (parent != null) {
                    parent.getSubResources().add(resourceRepresentation);
                } else {
                    resourceRepresentations.add(resourceRepresentation);
                }
            } else {
                resourceRepresentations.add(resourceRepresentation);
            }
        }
        return resourceRepresentations;
    }

    /**
     * @param authorizationProvider
     * @param resourceServer
     * @param clientId
     * @param userId
     * @return
     */
    private Set<Resource> getResourceByUser(AuthorizationProvider authorizationProvider, ResourceServer resourceServer, String clientId, String userId) {
        StoreFactory storeFactory = authorizationProvider.getStoreFactory();
        PolicyStore policyStore = storeFactory.getPolicyStore();
        ClientModel clientModel = realm.getClientById(clientId);
        final Set<Resource> resources = new LinkedHashSet<>();
        if (clientModel != null && resourceServer != null && auth.clients().canView(clientModel)) {
            UserModel user = session.users().getUserById(userId, realm);
            Set<RoleModel> userRoles = new HashSet<>();
            Set<RoleModel> roles = clientModel.getRoles();
            for (RoleModel roleModel : roles) {
                if (user.hasRole(roleModel)) {
                    userRoles.add(roleModel);
                }
            }
            policyStore.findByType("resource", resourceServer.getId()).forEach(policy -> {
                resources.addAll(policyToResource(authorizationProvider, resourceServer, userRoles, policy));
            });
            policyStore.findByType("scope", resourceServer.getId()).forEach(policy -> {
                resources.addAll(policyScopeToResource(authorizationProvider, resourceServer, userRoles, policy));
            });
        } else {
            throw new ForbiddenException();
        }
        return resources;
    }


    /**
     * @param authorizationProvider
     * @param resourceServer
     * @param userRoles
     * @param policy
     */
    private Set<Resource> policyScopeToResource(AuthorizationProvider authorizationProvider, ResourceServer resourceServer, Set<RoleModel> userRoles, Policy policy) {
        Set<Policy> associatedPolicies = policy.getAssociatedPolicies();
        Iterator<Policy> associatedPoliciesIterable = associatedPolicies.iterator();
        Set<Resource> resources = new LinkedHashSet<>();
        while (associatedPoliciesIterable.hasNext()) {
            Policy associatedPolicie = associatedPoliciesIterable.next();
            String roles = associatedPolicie.getConfig().get("roles");
            if (roles != null) {
                List<Map<String, Object>> roleConfig;
                try {
                    roleConfig = JsonSerialization.readValue(roles, List.class);
                } catch (Exception e) {
                    throw new RuntimeException("Malformed configuration for role policy [" + policy.getName() + "].", e);
                }
                if (!roleConfig.isEmpty()) {
                    for (Map<String, Object> roleMap : roleConfig) {
                        if (containRole(userRoles, String.valueOf(roleMap.get("id")))) {
                            for (Resource resource : authorizationProvider.getStoreFactory().getResourceStore().findByScope(policy.getScopes().stream().map(Scope::getId).collect(Collectors.toList()), resourceServer.getId())) {
                                resources.add(resource);
                            }
                        }
                    }
                }
            }
        }
        return resources;
    }

    /**
     * @param authorizationProvider
     * @param resourceServer
     * @param userRoles
     * @param policy
     */
    private Set<Resource> policyToResource(AuthorizationProvider authorizationProvider, ResourceServer resourceServer, Set<RoleModel> userRoles, Policy policy) {
        Set<Policy> associatedPolicies = policy.getAssociatedPolicies();
        Iterator<Policy> associatedPoliciesIterable = associatedPolicies.iterator();
        Set<Resource> resources = new LinkedHashSet<>();
        while (associatedPoliciesIterable.hasNext()) {
            Policy associatedPolicie = associatedPoliciesIterable.next();
            String roles = associatedPolicie.getConfig().get("roles");
            if (roles != null) {
                List<Map<String, Object>> roleConfig;
                try {
                    roleConfig = JsonSerialization.readValue(roles, List.class);
                } catch (Exception e) {
                    throw new RuntimeException("Malformed configuration for role policy [" + policy.getName() + "].", e);
                }
                if (!roleConfig.isEmpty()) {
                    for (Map<String, Object> roleMap : roleConfig) {
                        if (containRole(userRoles, String.valueOf(roleMap.get("id")))) {
                            for (Resource resource : policy.getResources()) {
                                resources.add(resource);
                            }
                        }
                    }
                }
            }
        }
        return resources;
    }


    @Path("{id}/clientRoleSubResourceByUser/{userId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    public Set<ResourceRepresentation> getClientRoleSubResourceByUser(final @PathParam("id") String id, final @PathParam("userId") String userId) {
        auth.clients().requireView();
        AuthorizationProvider authorizationProvider = session.getProvider(AuthorizationProvider.class);
        StoreFactory storeFactory = authorizationProvider.getStoreFactory();
        ResourceServer resourceServer = storeFactory.getResourceServerStore().findById(id);
        final Set<Resource> resources = getResourceByUser(authorizationProvider, resourceServer, id, userId);
        Map<String, ResourceRepresentation> alls = Maps.newLinkedHashMap();
        Set<ResourceRepresentation> allsResource = new LinkedHashSet<>();
        for (Resource resource : resources) {
            ResourceRepresentation resourceRepresentation = ModelToRepresentation.toRepresentation(resource, resourceServer, authorizationProvider, false);
            String parentKey = resource.getSingleAttribute("parent");
            resourceRepresentation.setParentId(parentKey);
            allsResource.add(resourceRepresentation);
            alls.put(resource.getName(), resourceRepresentation);
        }
        return toResource(allsResource, alls);
    }


    /**
     * 是否包角色
     *
     * @param roleModels
     * @param roleId
     * @return
     */
    private boolean containRole(Set<RoleModel> roleModels, String roleId) {
        boolean bool = false;
        for (RoleModel roleModel : roleModels) {
            if (roleModel.getId().equals(roleId)) {
                bool = true;
                break;
            }
        }
        return bool;
    }


    @Path("{id}/clientResourceByUser/{userId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    public Set<ResourceRepresentation> getClientResourceByUser(final @PathParam("id") String id, final @PathParam("userId") String userId,
                                                               final @QueryParam("name") String name) {
        auth.clients().requireView();
        AuthorizationProvider authorizationProvider = session.getProvider(AuthorizationProvider.class);
        StoreFactory storeFactory = authorizationProvider.getStoreFactory();
        ResourceServer resourceServer = storeFactory.getResourceServerStore().findById(id);
        final Set<Resource> resources = getResourceByUser(authorizationProvider, resourceServer, id, userId, name);
        Map<String, ResourceRepresentation> alls = Maps.newLinkedHashMap();
        Set<ResourceRepresentation> allsResource = new LinkedHashSet<>();
        for (Resource resource : resources) {
            ResourceRepresentation resourceRepresentation = ModelToRepresentation.toRepresentation(resource, resourceServer, authorizationProvider, false);
            resourceRepresentation.setParentId(resource.getParentId());
            allsResource.add(resourceRepresentation);
            alls.put(resource.getId(), resourceRepresentation);
        }
        return toResource(allsResource, alls);
    }

    /**
     * @param authorizationProvider
     * @param resourceServer
     * @param clientId
     * @param userId
     * @param resourceName
     * @return
     */
    private Set<Resource> getResourceByUser(AuthorizationProvider authorizationProvider, ResourceServer resourceServer, String clientId, String userId, String resourceName) {
        StoreFactory storeFactory = authorizationProvider.getStoreFactory();
        PolicyStore policyStore = storeFactory.getPolicyStore();
        ClientModel clientModel = realm.getClientById(clientId);
        final Set<Resource> resources = new LinkedHashSet<>();
        if (clientModel != null && resourceServer != null && auth.clients().canView(clientModel)) {
            UserModel user = session.users().getUserById(userId, realm);
            Set<RoleModel> userRoles = new HashSet<>();
            Set<RoleModel> roles = clientModel.getRoles();
            for (RoleModel roleModel : roles) {
                if (user.hasRole(roleModel)) {
                    userRoles.add(roleModel);
                }
            }
            policyStore.findByType("resource", resourceServer.getId()).forEach(policy -> {
                resources.addAll(policyToResource(authorizationProvider, resourceServer, userRoles, policy, resourceName));
            });
        } else {
            throw new ForbiddenException();
        }
        return resources;
    }


    /**
     * @param authorizationProvider
     * @param resourceServer
     * @param userRoles
     * @param policy
     */
    private Set<Resource> policyToResource(AuthorizationProvider authorizationProvider, ResourceServer resourceServer, Set<RoleModel> userRoles, Policy policy, String resourceName) {
        Set<Policy> associatedPolicies = policy.getAssociatedPolicies();
        Iterator<Policy> associatedPoliciesIterable = associatedPolicies.iterator();
        Set<Resource> resources = new LinkedHashSet<>();
        Resource parent = authorizationProvider.getStoreFactory().getResourceStore().findByName(resourceName, resourceServer.getId());
        if (parent == null) {
            return resources;
        }
        while (associatedPoliciesIterable.hasNext()) {
            Policy associatedPolicie = associatedPoliciesIterable.next();
            String roles = associatedPolicie.getConfig().get("roles");
            if (roles != null) {
                List<Map<String, Object>> roleConfig;
                try {
                    roleConfig = JsonSerialization.readValue(roles, List.class);
                } catch (Exception e) {
                    throw new RuntimeException("Malformed configuration for role policy [" + policy.getName() + "].", e);
                }
                if (!roleConfig.isEmpty()) {
                    for (Map<String, Object> roleMap : roleConfig) {
                        if (containRole(userRoles, String.valueOf(roleMap.get("id")))) {
                            for (Resource resource : policy.getResources()) {
                                if (parent.getId().equals(resource.getId()) ||
                                        (resource.getParentId() != null && resource.getParentId().equals(parent.getId()))) {
                                    resources.add(resource);
                                }
                            }
                        }
                    }
                }
            }
        }
        return resources;
    }


    @Path("{id}/clientResourcePermissionByUser/{userId}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    public Set<ResourceRepresentation> getClientResourcePermissionByUser(final @PathParam("id") String id, final @PathParam("userId") String userId,
                                                                         final @QueryParam("permission") String permission) {
        auth.clients().requireView();
        AuthorizationProvider authorizationProvider = session.getProvider(AuthorizationProvider.class);
        StoreFactory storeFactory = authorizationProvider.getStoreFactory();
        ResourceServer resourceServer = storeFactory.getResourceServerStore().findById(id);
        final Set<Resource> resources = getResourcePermissionByUser(authorizationProvider, resourceServer, id, userId, permission);
        Map<String, ResourceRepresentation> alls = Maps.newLinkedHashMap();
        Set<ResourceRepresentation> allsResource = new LinkedHashSet<>();
        for (Resource resource : resources) {
            ResourceRepresentation resourceRepresentation = ModelToRepresentation.toRepresentation(resource, resourceServer, authorizationProvider, false);
            resourceRepresentation.setParentId(resource.getParentId());
            allsResource.add(resourceRepresentation);
            alls.put(resource.getId(), resourceRepresentation);
        }
        return toResource(allsResource, alls);
    }

    /**
     * @param authorizationProvider
     * @param resourceServer
     * @param clientId
     * @param userId
     * @param permission
     * @return
     */
    private Set<Resource> getResourcePermissionByUser(AuthorizationProvider authorizationProvider, ResourceServer resourceServer, String clientId, String userId, String permission) {
        StoreFactory storeFactory = authorizationProvider.getStoreFactory();
        PolicyStore policyStore = storeFactory.getPolicyStore();
        ClientModel clientModel = realm.getClientById(clientId);
        final Set<Resource> resources = new LinkedHashSet<>();
        if (clientModel != null && resourceServer != null && auth.clients().canView(clientModel)) {
            UserModel user = session.users().getUserById(userId, realm);
            Set<RoleModel> userRoles = new HashSet<>();
            Set<RoleModel> roles = clientModel.getRoles();
            for (RoleModel roleModel : roles) {
                if (user.hasRole(roleModel)) {
                    userRoles.add(roleModel);
                }
            }
            policyStore.findByType("resource", resourceServer.getId()).forEach(policy -> {
                resources.addAll(policyToResourcePermission(authorizationProvider, resourceServer, userRoles, policy, permission));
            });
        } else {
            throw new ForbiddenException();
        }
        return resources;
    }


    /**
     * @param authorizationProvider
     * @param resourceServer
     * @param userRoles
     * @param policy
     */
    private Set<Resource> policyToResourcePermission(AuthorizationProvider authorizationProvider, ResourceServer resourceServer, Set<RoleModel> userRoles, Policy policy, String permission) {
        Set<Policy> associatedPolicies = policy.getAssociatedPolicies();
        Iterator<Policy> associatedPoliciesIterable = associatedPolicies.iterator();
        Set<Resource> resources = new LinkedHashSet<>();
        List<Resource> resourcePermissions = authorizationProvider.getStoreFactory().getResourceStore().findResourceIdByPermission(resourceServer.getId(), permission);
        while (associatedPoliciesIterable.hasNext()) {
            Policy associatedPolicie = associatedPoliciesIterable.next();
            String roles = associatedPolicie.getConfig().get("roles");
            if (roles != null) {
                List<Map<String, Object>> roleConfig;
                try {
                    roleConfig = JsonSerialization.readValue(roles, List.class);
                } catch (Exception e) {
                    throw new RuntimeException("Malformed configuration for role policy [" + policy.getName() + "].", e);
                }
                if (!roleConfig.isEmpty()) {
                    for (Map<String, Object> roleMap : roleConfig) {
                        if (containRole(userRoles, String.valueOf(roleMap.get("id")))) {
                            for (Resource resource : policy.getResources()) {
                                if (containResource(resourcePermissions, resource)) {
                                    resources.add(resource);
                                }
                            }
                        }
                    }
                }
            }
        }
        return resources;
    }


    /**
     * 是否包含资源
     *
     * @param resources
     * @param contain
     * @return
     */
    private boolean containResource(List<Resource> resources, Resource contain) {
        boolean bool = false;
        for (Resource resource : resources) {
            if (resource.getId().equals(resource.getId()) || contain.getParentId().equals(resource.getId())) {
                bool = true;
                break;
            }
        }
        return bool;
    }


}
