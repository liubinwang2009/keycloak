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

import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.NotFoundException;

import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.util.ObjectUtil;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.Constants;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.models.utils.RepresentationToModel;
import org.keycloak.policy.PasswordPolicyNotMetException;
import org.keycloak.representations.idm.FederatedIdentityRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.ForbiddenException;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;
import org.keycloak.services.resources.admin.permissions.UserPermissionEvaluator;
import org.keycloak.util.JsonSerialization;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base resource for managing users
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 * @resource Users
 */
public class UsersResource {

    private static final Logger logger = Logger.getLogger(UsersResource.class);
    private static final String SEARCH_ID_PARAMETER = "id:";

    protected RealmModel realm;

    private AdminPermissionEvaluator auth;

    private AdminEventBuilder adminEvent;

    @Context
    protected ClientConnection clientConnection;

    @Context
    protected KeycloakSession session;

    @Context
    protected HttpHeaders headers;

    public UsersResource(RealmModel realm, AdminPermissionEvaluator auth, AdminEventBuilder adminEvent) {
        this.auth = auth;
        this.realm = realm;
        this.adminEvent = adminEvent.resource(ResourceType.USER);
    }

    /**
     * Create a new user
     * <p>
     * Username must be unique.
     *
     * @param rep
     * @return
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createUser(final UserRepresentation rep) {
        auth.users().requireManage();

        String username = rep.getUsername();
        if (realm.isRegistrationEmailAsUsername()) {
            username = rep.getEmail();
        }
        if (ObjectUtil.isBlank(username)) {
            return ErrorResponse.error("User name is missing", Response.Status.BAD_REQUEST);
        }

        // Double-check duplicated username and email here due to federation
        if (session.users().getUserByUsername(username, realm) != null) {
            return ErrorResponse.exists("User exists with same username");
        }
        if (rep.getEmail() != null && !realm.isDuplicateEmailsAllowed() && session.users().getUserByEmail(rep.getEmail(), realm) != null) {
            return ErrorResponse.exists("User exists with same email");
        }

        if (!ObjectUtil.isBlank(rep.getIdcard()) && session.users().getUserByIdcard(rep.getIdcard(), realm) != null) {
            return ErrorResponse.exists("User exists with same idcard ");
        }

        try {
            UserModel user = session.users().addUser(realm, username);
            Set<String> emptySet = Collections.emptySet();

            UserResource.updateUserFromRep(user, rep, emptySet, realm, session, false);
            RepresentationToModel.createFederatedIdentities(rep, session, realm, user);
            RepresentationToModel.createGroups(rep, realm, user);

            RepresentationToModel.createCredentials(rep, session, realm, user, true);
            adminEvent.operation(OperationType.CREATE).resourcePath(session.getContext().getUri(), user.getId()).representation(rep).success();

            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().commit();
            }

            UserCache cache = session.getProvider(UserCache.class);
            if (cache != null) {
                cache.clear();
            }
            return Response.created(session.getContext().getUri().getAbsolutePathBuilder().path(user.getId()).build()).build();
        } catch (ModelDuplicateException e) {
            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().setRollbackOnly();
            }
            return ErrorResponse.exists("User exists with same username or email");
        } catch (PasswordPolicyNotMetException e) {
            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().setRollbackOnly();
            }
            return ErrorResponse.error("Password policy not met", Response.Status.BAD_REQUEST);
        } catch (ModelException me) {
            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().setRollbackOnly();
            }
            logger.warn("Could not create user", me);
            return ErrorResponse.error("Could not create user", Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Get representation of the user
     *
     * @param id User id
     * @return
     */
    @Path("{id}")
    public UserResource user(final @PathParam("id") String id) {
        UserModel user = session.users().getUserById(id, realm);
        if (user == null) {
            // we do this to make sure somebody can't phish ids
            if (auth.users().canQuery()) throw new NotFoundException("User not found");
            else throw new ForbiddenException();
        }
        UserResource resource = new UserResource(realm, user, auth, adminEvent);
        ResteasyProviderFactory.getInstance().injectProperties(resource);
        //resourceContext.initResource(users);
        return resource;
    }

    /**
     * Get users
     * <p>
     * Returns a list of users, filtered according to query parameters
     *
     * @param search     A String contained in username, first or last name, or email
     * @param last
     * @param first
     * @param email
     * @param username
     * @param first      Pagination offset
     * @param maxResults Maximum results size (defaults to 100)
     * @return
     */
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public List<UserRepresentation> getUsers(@QueryParam("search") String search,
                                             @QueryParam("lastName") String last,
                                             @QueryParam("firstName") String first,
                                             @QueryParam("email") String email,
                                             @QueryParam("username") String username,
                                             @QueryParam("idcard") String idcard,
                                             @QueryParam("phone") String phone,
                                             @QueryParam("unitCode") String unitCode,
                                             @QueryParam("first") Integer firstResult,
                                             @QueryParam("max") Integer maxResults,
                                             @QueryParam("attrName") String attrName,
                                             @QueryParam("attrValue") String attrValue,
                                             @QueryParam("briefRepresentation") Boolean briefRepresentation,
                                             @QueryParam("exact") Boolean exact) {
        UserPermissionEvaluator userPermissionEvaluator = auth.users();

        userPermissionEvaluator.requireQuery();

        firstResult = firstResult != null ? firstResult : -1;
        maxResults = maxResults != null ? maxResults : Constants.DEFAULT_MAX_RESULTS;

        List<UserModel> userModels = Collections.emptyList();
        if (search != null) {
            if (search.startsWith(SEARCH_ID_PARAMETER)) {
                UserModel userModel = session.users().getUserById(search.substring(SEARCH_ID_PARAMETER.length()).trim(), realm);
                if (userModel != null) {
                    userModels = Arrays.asList(userModel);
                }
                //暂时保留
            } else if (search.indexOf(":") != -1 && search.split(":").length > 1) {
                String[] searchs = search.split(":");
                userModels = session.users().searchForUserByUserAttribute(searchs[0], searchs[1], realm);
            } else {
                Map<String, String> attributes = new HashMap<>();
                attributes.put(UserModel.SEARCH, search.trim());
                return searchForUser(attributes, realm, userPermissionEvaluator, briefRepresentation, firstResult, maxResults, false);
            }
        } else if (attrName != null && attrValue != null) {
            userModels = session.users().searchForUserByUserAttribute(attrName, attrValue, realm);
        } else if (last != null || first != null || email != null || username != null || idcard != null || unitCode != null || phone != null || exact != null) {
            Map<String, String> attributes = new HashMap<>();
            if (last != null) {
                attributes.put(UserModel.LAST_NAME, last);
            }
            if (first != null) {
                attributes.put(UserModel.FIRST_NAME, first);
            }
            if (email != null) {
                attributes.put(UserModel.EMAIL, email);
            }
            if (username != null) {
                attributes.put(UserModel.USERNAME, username);
            }
            if (idcard != null) {
                attributes.put(UserModel.IDCARD, idcard);
            }
            if (unitCode != null) {
                attributes.put(UserModel.UNIT_CODE, unitCode);
            }
            if (phone != null) {
                attributes.put(UserModel.PHONE, phone);
            }
            if (exact != null) {
                attributes.put(UserModel.EXACT, exact.toString());
            }
            return searchForUser(attributes, realm, userPermissionEvaluator, briefRepresentation, firstResult, maxResults, true);
        } else {
            return searchForUser(new HashMap<>(), realm, userPermissionEvaluator, briefRepresentation, firstResult, maxResults, false);
        }

        return toRepresentation(realm, userPermissionEvaluator, briefRepresentation, userModels);
    }

    /**
     * Returns the number of users that match the given criteria.
     * It can be called in three different ways.
     * 1. Don't specify any criteria and pass {@code null}. The number of all
     * users within that realm will be returned.
     * <p>
     * 2. If {@code search} is specified other criteria such as {@code last} will
     * be ignored even though you set them. The {@code search} string will be
     * matched against the first and last name, the username and the email of a
     * user.
     * <p>
     * 3. If {@code search} is unspecified but any of {@code last}, {@code first},
     * {@code email} or {@code username} those criteria are matched against their
     * respective fields on a user entity. Combined with a logical and.
     *
     * @param search   arbitrary search string for all the fields below
     * @param last     last name filter
     * @param first    first name filter
     * @param email    email filter
     * @param username username filter
     * @return the number of users that match the given criteria
     */
    @Path("count")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Integer getUsersCount(@QueryParam("search") String search,
                                 @QueryParam("lastName") String last,
                                 @QueryParam("firstName") String first,
                                 @QueryParam("email") String email,
                                 @QueryParam("username") String username,
                                 @QueryParam("idcard") String idcard,
                                 @QueryParam("phone") String phone,
                                 @QueryParam("unitCode") String unitCode) {
        UserPermissionEvaluator userPermissionEvaluator = auth.users();
        userPermissionEvaluator.requireQuery();

        if (search != null) {
            if (search.startsWith(SEARCH_ID_PARAMETER)) {
                UserModel userModel = session.users().getUserById(search.substring(SEARCH_ID_PARAMETER.length()).trim(), realm);
                return userModel != null && userPermissionEvaluator.canView(userModel) ? 1 : 0;
            } else if (userPermissionEvaluator.canView()) {
                return session.users().getUsersCount(search.trim(), realm);
            } else {
                return session.users().getUsersCount(search.trim(), realm, auth.groups().getGroupsWithViewPermission());
            }
        } else if (last != null || first != null || email != null || username != null || idcard != null || unitCode != null || phone != null) {
            Map<String, String> parameters = new HashMap<>();
            if (last != null) {
                parameters.put(UserModel.LAST_NAME, last);
            }
            if (first != null) {
                parameters.put(UserModel.FIRST_NAME, first);
            }
            if (email != null) {
                parameters.put(UserModel.EMAIL, email);
            }
            if (username != null) {
                parameters.put(UserModel.USERNAME, username);
            }
            if (idcard != null) {
                parameters.put(UserModel.IDCARD, idcard);
            }
            if (unitCode != null) {
                parameters.put(UserModel.UNIT_CODE, unitCode);
            }
            if (phone != null) {
                parameters.put(UserModel.PHONE, phone);
            }
            if (userPermissionEvaluator.canView()) {
                return session.users().getUsersCount(parameters, realm);
            } else {
                return session.users().getUsersCount(parameters, realm, auth.groups().getGroupsWithViewPermission());
            }
        } else if (userPermissionEvaluator.canView()) {
            return session.users().getUsersCount(realm);
        } else {
            return session.users().getUsersCount(realm, auth.groups().getGroupsWithViewPermission());
        }
    }

    private List<UserRepresentation> searchForUser(Map<String, String> attributes, RealmModel realm, UserPermissionEvaluator usersEvaluator, Boolean briefRepresentation, Integer firstResult, Integer maxResults, Boolean includeServiceAccounts) {
        session.setAttribute(UserModel.INCLUDE_SERVICE_ACCOUNT, includeServiceAccounts);

        if (!auth.users().canView()) {
            Set<String> groupModels = auth.groups().getGroupsWithViewPermission();

            if (!groupModels.isEmpty()) {
                session.setAttribute(UserModel.GROUPS, groupModels);
            }
        }

        List<UserModel> userModels = session.users().searchForUser(attributes, realm, firstResult, maxResults);

        return toRepresentation(realm, usersEvaluator, briefRepresentation, userModels);
    }

    private List<UserRepresentation> toRepresentation(RealmModel realm, UserPermissionEvaluator usersEvaluator, Boolean briefRepresentation, List<UserModel> userModels) {
        boolean briefRepresentationB = briefRepresentation != null && briefRepresentation;
        List<UserRepresentation> results = new ArrayList<>();
        boolean canViewGlobal = usersEvaluator.canView();

        usersEvaluator.grantIfNoPermission(session.getAttribute(UserModel.GROUPS) != null);

        for (UserModel user : userModels) {
            if (!canViewGlobal) {
                if (!usersEvaluator.canView(user)) {
                    continue;
                }
            }
            UserRepresentation userRep = briefRepresentationB
                    ? ModelToRepresentation.toBriefRepresentation(user)
                    : ModelToRepresentation.toRepresentation(session, realm, user);
            userRep.setAccess(usersEvaluator.getAccess(user));
            results.add(userRep);
        }
        return results;
    }


    @Path("username/{username}")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public UserRepresentation getUserByUsername(final @PathParam("username") String username) {
        UserModel user = session.users().getUserByUsername(username, realm);
        if (user == null) {
            if (auth.users().canQuery()) return null;
            else throw new ForbiddenException();
        }
        return ModelToRepresentation.toRepresentation(session, realm, user);
    }


    @GET
    @NoCache
    @Path("idcard/{idcard}")
    @Produces(MediaType.APPLICATION_JSON)
    public UserRepresentation getUserByIdcard(@PathParam("idcard") String idcard) {
        UserModel user = session.users().getUserByIdcard(idcard, realm);
        if (user == null) {
            if (auth.users().canQuery()) return null;
            else throw new ForbiddenException();
        }
        return ModelToRepresentation.toRepresentation(session, realm, user);
    }


    @GET
    @NoCache
    @Path("phone/{phone}")
    @Produces(MediaType.APPLICATION_JSON)
    public UserRepresentation getUserByPhone(@PathParam("phone") String phone) {
        UserModel user = session.users().getUserByPhone(phone, realm);
        if (user == null) {
            if (auth.users().canQuery()) return null;
            else throw new ForbiddenException();
        }
        return ModelToRepresentation.toRepresentation(session, realm, user);
    }
}
