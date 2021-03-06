/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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

package org.keycloak.models.cache.infinispan.authorization.entities;

import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.Scope;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.models.GroupModel;
import org.keycloak.models.cache.infinispan.DefaultLazyLoader;
import org.keycloak.models.cache.infinispan.LazyLoader;
import org.keycloak.models.cache.infinispan.authorization.ResourceAdapter;
import org.keycloak.models.cache.infinispan.entities.AbstractRevisioned;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class CachedResource extends AbstractRevisioned implements InResourceServer {

    private final String resourceServerId;
    private final String iconUri;
    private final String owner;
    private final String type;
    private final String name;
    private final String displayName;
    private final boolean ownerManagedAccess;
    private LazyLoader<Resource, Set<String>> scopesIds;
    private LazyLoader<Resource, Set<String>> uris;
    private LazyLoader<Resource, MultivaluedHashMap<String, String>> attributes;
    private Resource parent;
    private String parentId;
    private LazyLoader<Resource, Set<String>> subResources;
    private Integer sort;
    private String permission;
    private boolean enabled;


    public CachedResource(Long revision, Resource resource) {
        super(revision, resource.getId());
        this.name = resource.getName();
        this.displayName = resource.getDisplayName();
        this.type = resource.getType();
        this.owner = resource.getOwner();
        this.iconUri = resource.getIconUri();
        this.resourceServerId = resource.getResourceServer().getId();
        ownerManagedAccess = resource.isOwnerManagedAccess();

        if (resource.isFetched("uris")) {
            Set<String> data = new HashSet<>(resource.getUris());
            this.uris = source -> data;
        } else {
            this.uris = new DefaultLazyLoader<>(source -> new HashSet<>(source.getUris()), Collections::emptySet);
        }

        if (resource.isFetched("scopes")) {
            Set<String> data = resource.getScopes().stream().map(Scope::getId).collect(Collectors.toSet());
            this.scopesIds = source -> data;
        } else {
            this.scopesIds = new DefaultLazyLoader<>(source -> source.getScopes().stream().map(Scope::getId).collect(Collectors.toSet()), Collections::emptySet);
        }

        if (resource.isFetched("attributes")) {
            MultivaluedHashMap<String, String> data = new MultivaluedHashMap<>(resource.getAttributes());
            this.attributes = source -> data;
        } else {
            this.attributes = new DefaultLazyLoader<>(source -> new MultivaluedHashMap<>(source.getAttributes()), MultivaluedHashMap::new);
        }
        this.parent = resource.getParent();
        this.parentId = resource.getParentId();
        if (resource.isFetched("subResources")) {
            this.subResources = new DefaultLazyLoader<>(source -> source.getSubResources().stream().map(Resource::getId).collect(Collectors.toSet()), Collections::emptySet);
        }
        this.sort = resource.getSort();
        this.permission = resource.getPermission();
        this.enabled = resource.isEnabled();
    }


    public String getName() {
        return this.name;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public Set<String> getUris(Supplier<Resource> source) {
        return this.uris.get(source);
    }

    public String getType() {
        return this.type;
    }

    public String getIconUri() {
        return this.iconUri;
    }

    public String getOwner() {
        return this.owner;
    }

    public boolean isOwnerManagedAccess() {
        return ownerManagedAccess;
    }

    public String getResourceServerId() {
        return this.resourceServerId;
    }

    public Set<String> getScopesIds(Supplier<Resource> source) {
        return this.scopesIds.get(source);
    }

    public Map<String, List<String>> getAttributes(Supplier<Resource> source) {
        return attributes.get(source);
    }

    public Resource getParent() {
        return parent;
    }

    public Set<String> getSubResources(Supplier<Resource> resource) {
        return subResources.get(resource);
    }

    public Integer getSort() {
        return sort;
    }

    public String getPermission() {
        return permission;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getParentId() {
        return parentId;
    }
}
