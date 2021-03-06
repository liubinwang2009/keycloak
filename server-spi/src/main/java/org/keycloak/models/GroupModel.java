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

package org.keycloak.models;

import org.keycloak.provider.ProviderEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public interface GroupModel extends RoleMapperModel {
    /**
     * Automatically calls setParent() on the subGroup
     *
     * @param subGroup
     */
    void addChild(GroupModel subGroup);

    /**
     * @param name
     * @return list of all attribute values or empty list if there are not any values. Never return null
     */
    List<String> getAttribute(String name);

    Map<String, List<String>> getAttributes();

    /**
     * @param name
     * @return null if there is not any value of specified attribute or first value otherwise. Don't throw exception if there are more values of the attribute
     */
    String getFirstAttribute(String name);

    String getId();

    String getName();

    void setName(String name);

    GroupModel getParent();

    /**
     * You must also call addChild on the parent group, addChild on RealmModel if there is no parent group
     *
     * @param group
     */
    void setParent(GroupModel group);

    String getParentId();

    Set<GroupModel> getSubGroups();

    Long getUserCount();

    Long getUserAllCount();

    boolean isHasChild();

    void setHasChild(boolean hasChild);

    void removeAttribute(String name);

    /**
     * Automatically calls setParent() on the subGroup
     *
     * @param subGroup
     */
    void removeChild(GroupModel subGroup);

    void setAttribute(String name, List<String> values);

    /**
     * Set single value of specified attribute. Remove all other existing values
     *
     * @param name
     * @param value
     */
    void setSingleAttribute(String name, String value);


    interface GroupRemovedEvent extends ProviderEvent {
        GroupModel getGroup();

        KeycloakSession getKeycloakSession();

        RealmModel getRealm();
    }
}
