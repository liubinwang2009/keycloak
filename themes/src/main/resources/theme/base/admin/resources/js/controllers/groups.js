module.controller('GroupListCtrl', function($scope, $route, $q, realm, Groups, GroupsCount, Group, GroupChildren, Notifications, $location, Dialog, ComponentUtils, $translate) {
    $scope.realm = realm;
    $scope.groupList = [
        {
            "id" : "realm",
            "name": $translate.instant('groups'),
            "subGroups" : []
        }
    ];

    $scope.searchCriteria = '';
    $scope.currentPage = 1;
    $scope.currentPageInput = $scope.currentPage;
    $scope.pageSize = 20;
    $scope.numberOfPages = 1;
    $scope.tree = [];

    var refreshGroups = function (search) {
        console.log('refreshGroups');
        $scope.currentPageInput = $scope.currentPage;

        var first = ($scope.currentPage * $scope.pageSize) - $scope.pageSize;
        console.log('first:' + first);
        var queryParams = {
            realm : realm.realm,
            first : first,
            max : $scope.pageSize
        };
        var countParams = {
            realm : realm.realm,
            top : 'true'
        };

        if(angular.isDefined(search) && search !== '') {
            queryParams.search = search;
            countParams.search = search;
        }

        var promiseGetGroups = $q.defer();
        Groups.query(queryParams, function(entry) {
            promiseGetGroups.resolve(entry);
        }, function() {
            promiseGetGroups.reject($translate.instant('group.fetch.fail', {params: queryParams}));
        });
        promiseGetGroups.promise.then(function(groups) {
            $scope.groupList = [
                {
                    "id" : "realm",
                    "name": $translate.instant('groups'),
                    "subGroups": ComponentUtils.sortGroups('name', groups)
                }
            ];
            if (angular.isDefined(search) && search !== '') {
                // Add highlight for concrete text match
                setTimeout(function () {
                    document.querySelectorAll('span').forEach(function (element) {
                        if (element.textContent.indexOf(search) != -1) {
                            angular.element(element).addClass('highlight');
                        }
                    });
                }, 500);
            }
        }, function (failed) {
            Notifications.error(failed);
        });

        var promiseCount = $q.defer();
        console.log('countParams: realm[' + countParams.realm);
        GroupsCount.query(countParams, function(entry) {
            promiseCount.resolve(entry);
        }, function() {
            promiseCount.reject($translate.instant('group.fetch.fail', {params: countParams}));
        });
        promiseCount.promise.then(function(entry) {
            if(angular.isDefined(entry.count) && entry.count > $scope.pageSize) {
                $scope.numberOfPages = Math.ceil(entry.count/$scope.pageSize);
            } else {
                $scope.numberOfPages = 1;
            }
        }, function (failed) {
            Notifications.error(failed);
        });
    };
    refreshGroups();

    $scope.$watch('currentPage', function(newValue, oldValue) {
        if(parseInt(newValue, 10) !== oldValue) {
            refreshGroups($scope.searchCriteria);
        }
    });

    $scope.clearSearch = function() {
        $scope.searchCriteria = '';
        if (parseInt($scope.currentPage, 10) === 1) {
            refreshGroups();
        } else {
            $scope.currentPage = 1;
        }
    };

    $scope.searchGroup = function() {
        if (parseInt($scope.currentPage, 10) === 1) {
            refreshGroups($scope.searchCriteria);
        } else {
            $scope.currentPage = 1;
        }
    };

    $scope.edit = function(selected) {
        if (selected.id === 'realm') return;
        $location.url("/realms/" + realm.realm + "/groups/" + selected.id);
    };

    $scope.cut = function(selected) {
        $scope.cutNode = selected;
    };

    $scope.isDisabled = function() {
        if (!$scope.tree.currentNode) return true;
        return $scope.tree.currentNode.id === 'realm';
    };

    $scope.paste = function(selected) {
        if (selected === null) return;
        if ($scope.cutNode === null) return;
        if (selected.id === $scope.cutNode.id) return;
        if (selected.id === 'realm') {
            Groups.save({realm: realm.realm}, {id:$scope.cutNode.id}, function() {
                $route.reload();
                Notifications.success($translate.instant('group.move.success'));

            });

        } else {
            GroupChildren.save({realm: realm.realm, groupId: selected.id}, {id:$scope.cutNode.id}, function() {
                $route.reload();
                Notifications.success($translate.instant('group.move.success'));

            });

        }

    };

    $scope.remove = function(selected) {
        if (selected === null) return;
        Dialog.confirmWithButtonText(
            $translate.instant('group.remove.confirm.title', {name: selected.name}),
            $translate.instant('group.remove.confirm.message', {name: selected.name}),
            $translate.instant('dialogs.delete.confirm'),
            function() {
                Group.remove({ realm: realm.realm, groupId : selected.id }, function() {
                    $route.reload();
                    Notifications.success($translate.instant('group.remove.success'));
                });
            }
        );
        if (selected.hasChild) {
            Notifications.error("The group has children and cannot be deleted!");
            return;
        }
        Dialog.confirmDelete(selected.name, 'group', function() {
            Group.remove({ realm: realm.realm, groupId : selected.id }, function() {
                $route.reload();
                Notifications.success("The group has been deleted.");
            });
        });

    };

    $scope.createGroup = function(selected) {
        var parent = 'realm';
        if (selected) {
            parent = selected.id;
        }
        $location.url("/create/group/" + realm.realm + '/parent/' + parent);

    };
    var isLeaf = function(node) {
        return node.id !== "realm" && (!node.subGroups || node.subGroups.length === 0);
    };

    $scope.getGroupClass = function(node) {
        if (node.id === "realm") {
            return 'pficon pficon-users';
        }
        if(node.hasChild){
            return 'collapsed';
        }
        if (isLeaf(node)) {
            return 'normal';
        }
        if (node.subGroups.length && node.collapsed) return 'collapsed';
        if (node.subGroups.length && !node.collapsed) return 'expanded';
        return 'collapsed';

    };

    $scope.getSelectedClass = function(node) {
        if (node.selected) {
            return 'selected';
        } else if ($scope.cutNode && $scope.cutNode.id === node.id) {
            return 'cut';
        }
        return undefined;
    }

    $scope.tree.selectNodeHead = function(node) {
            node.collapsed = !node.collapsed;

        	if ((!node.subGroups || !node.subGroups.length ) && node.id != '' && node.hasChild){
    			var queryParams = {
    				realm : realm.realm,
    				first : 0,
    				max : $scope.pageSize,
    				parent: node.id
    			};
        	     Groups.query(queryParams, function(entry) {
                       promiseSubGroups.resolve(entry);
                 }, function() {
                       promiseSubGroups.reject('subGroups Unable to fetch ' + queryParams);
                 });
                 var promiseSubGroups = $q.defer();
                 var promiseGetGroupsChain   = promiseSubGroups.promise.then(function(groups) {
                       console.log('*** subGroups call groups size: ' + groups.length);
                       if(groups && groups.length > 0){
                       		node.subGroups = groups;
                       		node.collapsed = false;
                       }
                 });

        	}
     };
});

module.controller('GroupCreateCtrl', function($scope, $route, realm, parentId, Groups, Group, GroupChildren, Notifications, $location, $translate) {
    $scope.realm = realm;
    $scope.group = {};
    $scope.save = function() {
        console.log('save!!!');
        if (parentId === 'realm') {
            console.log('realm');
            Groups.save({realm: realm.realm}, $scope.group, function(data, headers) {
                var l = headers().location;


                var id = l.substring(l.lastIndexOf("/") + 1);

                $location.url("/realms/" + realm.realm + "/groups/" + id);
                Notifications.success($translate.instant('group.create.success'));
            })

        } else {
            GroupChildren.save({realm: realm.realm, groupId: parentId}, $scope.group, function(data, headers) {
                var l = headers().location;


                var id = l.substring(l.lastIndexOf("/") + 1);

                $location.url("/realms/" + realm.realm + "/groups/" + id);
                Notifications.success($translate.instant('group.create.success'));
            })

        }

    };
    $scope.cancel = function() {
        $location.url("/realms/" + realm.realm + "/groups");
    };
});

module.controller('GroupTabCtrl', function(Dialog, $scope, Current, Group, Notifications, $location, $translate) {
    $scope.removeGroup = function() {
        Dialog.confirmWithButtonText(
            $translate.instant('group.remove.confirm.title', {name: $scope.group.name}),
            $translate.instant('group.remove.confirm.message', {name: $scope.group.name}),
            $translate.instant('dialogs.delete.confirm'),
            function() {
                Group.remove({
                    realm : Current.realm.realm,
                    groupId : $scope.group.id
                }, function() {
                    $location.url("/realms/" + Current.realm.realm + "/groups");
                    Notifications.success($translate.instant('group.remove.success'));
                });
            }
        );
    };
});

module.controller('GroupDetailCtrl', function(Dialog, $scope, realm, group, Group, Notifications, $location, $translate) {
    $scope.realm = realm;

    if (!group.attributes) {
        group.attributes = {}
    }
    convertAttributeValuesToString(group);


    $scope.group = angular.copy(group);

    $scope.changed = false; // $scope.create;
    $scope.addDefaltKeys = true;
    $scope.$watch('group', function() {
        if (!angular.equals($scope.group, group)) {
            $scope.changed = true;
        }
    }, true);

    $scope.save = function() {
        convertAttributeValuesToLists();

        Group.update({
            realm: realm.realm,
            groupId: $scope.group.id
        }, $scope.group, function () {
            $scope.changed = false;
            convertAttributeValuesToString($scope.group);
            group = angular.copy($scope.group);
            Notifications.success($translate.instant('group.edit.success'));
        });
    };

    function convertAttributeValuesToLists() {
        var attrs = $scope.group.attributes;
        for (var attribute in attrs) {
            if (typeof attrs[attribute] === "string") {
                attrs[attribute] = attrs[attribute].split("##");
            }
        }
    }

    function convertAttributeValuesToString(group) {
        var attrs = group.attributes;
        for (var attribute in attrs) {
            if (typeof attrs[attribute] === "object") {
                attrs[attribute] = attrs[attribute].join("##");
            }
        }
    }

    $scope.reset = function() {
        $scope.group = angular.copy(group);
        $scope.changed = false;
    };

    $scope.cancel = function() {
        $location.url("/realms/" + realm.realm + "/groups");
    };

    $scope.addAttribute = function() {
        $scope.group.attributes[$scope.newAttribute.key] = $scope.newAttribute.value;
        delete $scope.newAttribute;
    }

    $scope.removeAttribute = function(key) {
        delete $scope.group.attributes[key];
        $scope.addDefaltKeys = true;
    }

    $scope.addDefaltAttribute = function(){
        var keys = ['code', 'parentCode',  'shortName', 'level'];
        var attrs = $scope.group.attributes;
        for(var i=0; i< keys.length; i++){
             if (!attrs[keys[i]]) {
                 $scope.group.attributes[keys[i]]=[];
             }
        }
        $scope.addDefaltKeys = false;
    }

});

module.controller('GroupRoleMappingCtrl', function($scope, $http, $route, realm, group, clients, client, Client, Notifications, GroupRealmRoleMapping,
                                                   GroupClientRoleMapping, GroupAvailableRealmRoleMapping, GroupAvailableClientRoleMapping,
                                                   GroupCompositeRealmRoleMapping, GroupCompositeClientRoleMapping, $translate) {
    $scope.realm = realm;
    $scope.group = group;
    $scope.selectedRealmRoles = [];
    $scope.selectedRealmMappings = [];
    $scope.realmMappings = [];
    $scope.clients = clients;
    $scope.client = client;
    $scope.clientRoles = [];
    $scope.clientComposite = [];
    $scope.selectedClientRoles = [];
    $scope.selectedClientMappings = [];
    $scope.clientMappings = [];
    $scope.dummymodel = [];

    $scope.realmMappings = GroupRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
    $scope.realmRoles = GroupAvailableRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
    $scope.realmComposite = GroupCompositeRealmRoleMapping.query({realm : realm.realm, groupId : group.id});

    $scope.addRealmRole = function() {
        $scope.selectedRealmRolesToAdd = JSON.parse('[' + $scope.selectedRealmRoles + ']');
        $scope.selectedRealmRoles = [];
        $http.post(authUrl + '/admin/realms/' + realm.realm + '/groups/' + group.id + '/role-mappings/realm',
            $scope.selectedRealmRolesToAdd).then(function() {
            $scope.realmMappings = GroupRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.realmRoles = GroupAvailableRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.realmComposite = GroupCompositeRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.selectedRealmMappings = [];
            $scope.selectRealmRoles = [];
            if ($scope.selectedClient) {
                console.log('load available');
                $scope.clientComposite = GroupCompositeClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
                $scope.clientRoles = GroupAvailableClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
                $scope.clientMappings = GroupClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
                $scope.selectedClientRoles = [];
                $scope.selectedClientMappings = [];
            }
            $scope.selectedRealmRolesToAdd = [];
            Notifications.success($translate.instant('group.roles.add.success'));

        });
    };

    $scope.deleteRealmRole = function() {
        $scope.selectedRealmMappingsToRemove = JSON.parse('[' + $scope.selectedRealmMappings + ']');
        $http.delete(authUrl + '/admin/realms/' + realm.realm + '/groups/' + group.id + '/role-mappings/realm',
            {data : $scope.selectedRealmMappingsToRemove, headers : {"content-type" : "application/json"}}).then(function() {
            $scope.realmMappings = GroupRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.realmRoles = GroupAvailableRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.realmComposite = GroupCompositeRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.selectedRealmMappings = [];
            $scope.selectRealmRoles = [];
            if ($scope.selectedClient) {
                console.log('load available');
                $scope.clientComposite = GroupCompositeClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
                $scope.clientRoles = GroupAvailableClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
                $scope.clientMappings = GroupClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
                $scope.selectedClientRoles = [];
                $scope.selectedClientMappings = [];
            }
            $scope.selectedRealmMappingsToRemove = [];
            Notifications.success($translate.instant('group.roles.remove.success'));
        });
    };

    $scope.addClientRole = function() {
        $scope.selectedClientRolesToAdd = JSON.parse('[' + $scope.selectedClientRoles + ']');
        $http.post(authUrl + '/admin/realms/' + realm.realm + '/groups/' + group.id + '/role-mappings/clients/' + $scope.selectedClient.id,
            $scope.selectedClientRolesToAdd).then(function() {
            $scope.clientMappings = GroupClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
            $scope.clientRoles = GroupAvailableClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
            $scope.clientComposite = GroupCompositeClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
            $scope.selectedClientRoles = [];
            $scope.selectedClientMappings = [];
            $scope.realmComposite = GroupCompositeRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.realmRoles = GroupAvailableRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.selectedClientRolesToAdd = [];
            Notifications.success($translate.instant('group.roles.add.success'));
        });
    };

    $scope.deleteClientRole = function() {
        $scope.selectedClientMappingsToRemove = JSON.parse('[' + $scope.selectedClientMappings + ']');
        $http.delete(authUrl + '/admin/realms/' + realm.realm + '/groups/' + group.id + '/role-mappings/clients/' + $scope.selectedClient.id,
            {data : $scope.selectedClientMappingsToRemove, headers : {"content-type" : "application/json"}}).then(function() {
            $scope.clientMappings = GroupClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
            $scope.clientRoles = GroupAvailableClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
            $scope.clientComposite = GroupCompositeClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
            $scope.selectedClientRoles = [];
            $scope.selectedClientMappings = [];
            $scope.realmComposite = GroupCompositeRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.realmRoles = GroupAvailableRealmRoleMapping.query({realm : realm.realm, groupId : group.id});
            $scope.selectedClientMappingsToRemove = [];
            Notifications.success($translate.instant('group.roles.remove.success'));
        });
    };


    $scope.changeClient = function(client) {
        $scope.selectedClient = client;
        if (!client || !client.id) {
            $scope.selectedClient = null;
            $scope.clientRoles = null;
            $scope.clientMappings = null;
            $scope.clientComposite = null;
            return;
        }
        if ($scope.selectedClient) {
            $scope.clientComposite = GroupCompositeClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
            $scope.clientRoles = GroupAvailableClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
            $scope.clientMappings = GroupClientRoleMapping.query({realm : realm.realm, groupId : group.id, client : $scope.selectedClient.id});
        }
        $scope.selectedClientRoles = [];
        $scope.selectedClientMappings = [];
    };

    clientSelectControl($scope, $route.current.params.realm, Client);

});

module.controller('GroupMembersCtrl', function($scope, realm, group, GroupMembership) {
    $scope.realm = realm;
    $scope.page = 0;
    $scope.group = group;

    $scope.query = {
        realm: realm.realm,
        groupId: group.id,
        max : 5,
        first : 0
    };


    $scope.firstPage = function() {
        $scope.query.first = 0;
        $scope.searchQuery();
    };

    $scope.previousPage = function() {
        $scope.query.first -= parseInt($scope.query.max);
        if ($scope.query.first < 0) {
            $scope.query.first = 0;
        }
        $scope.searchQuery();
    };

    $scope.nextPage = function() {
        $scope.query.first += parseInt($scope.query.max);
        $scope.searchQuery();
    };

    $scope.searchQuery = function() {
        console.log("query.search: " + $scope.query.search);
        $scope.searchLoaded = false;

        $scope.users = GroupMembership.query($scope.query, function() {
            console.log('search loaded');
            $scope.searchLoaded = true;
            $scope.lastSearch = $scope.query.search;
        });
    };

    $scope.searchQuery();

});

module.controller('DefaultGroupsCtrl', function($scope, $q, realm, Groups, GroupsCount, DefaultGroups, Notifications, $translate) {
    $scope.realm = realm;
    $scope.groupList = [];
    $scope.selectedGroup = null;
    $scope.tree = [];

    $scope.searchCriteria = '';
    $scope.currentPage = 1;
    $scope.currentPageInput = $scope.currentPage;
    $scope.pageSize = 20;
    $scope.numberOfPages = 1;

    var refreshDefaultGroups = function () {
        DefaultGroups.query({realm: realm.realm}, function(data) {
            $scope.defaultGroups = data;
        });
    }

    var refreshAvailableGroups = function (search) {
        var first = ($scope.currentPage * $scope.pageSize) - $scope.pageSize;
        $scope.currentPageInput = $scope.currentPage;
        var queryParams = {
            realm : realm.realm,
            first : first,
            max : $scope.pageSize
        };
        var countParams = {
            realm : realm.realm,
            top : 'true'
        };

        if(angular.isDefined(search) && search !== '') {
            queryParams.search = search;
            countParams.search = search;
        }

        var promiseGetGroups = $q.defer();
        Groups.query(queryParams, function(entry) {
            promiseGetGroups.resolve(entry);
        }, function() {
            promiseGetGroups.reject($translate.instant('group.fetch.fail', {params: queryParams}));
        });
        promiseGetGroups.promise.then(function(groups) {
            $scope.groupList = groups;
        }, function (failed) {
            Notifications.success(failed);
        });

        var promiseCount = $q.defer();
        GroupsCount.query(countParams, function(entry) {
            promiseCount.resolve(entry);
        }, function() {
            promiseCount.reject($translate.instant('group.fetch.fail', {params: countParams}));
        });
        promiseCount.promise.then(function(entry) {
            if(angular.isDefined(entry.count) && entry.count > $scope.pageSize) {
                $scope.numberOfPages = Math.ceil(entry.count/$scope.pageSize);
            }
        }, function (failed) {
            Notifications.success(failed);
        });
    };

    refreshAvailableGroups();

    $scope.$watch('currentPage', function(newValue, oldValue) {
        if(parseInt(newValue, 10) !== parseInt(oldValue, 10)) {
            refreshAvailableGroups($scope.searchCriteria);
        }
    });

    $scope.clearSearch = function() {
        $scope.searchCriteria = '';
        if (parseInt($scope.currentPage, 10) === 1) {
            refreshAvailableGroups();
        } else {
            $scope.currentPage = 1;
        }
    };

    $scope.searchGroup = function() {
        if (parseInt($scope.currentPage, 10) === 1) {
            refreshAvailableGroups($scope.searchCriteria);
        } else {
            $scope.currentPage = 1;
        }
    };

    refreshDefaultGroups();

    $scope.addDefaultGroup = function() {
        if (!$scope.tree.currentNode) {
            Notifications.error($translate.instant('group.default.add.error'));
            return;
        }

        DefaultGroups.update({realm: realm.realm, groupId: $scope.tree.currentNode.id}, function() {
            refreshDefaultGroups();
            Notifications.success($translate.instant('group.default.add.success'));
        });

    };

    $scope.removeDefaultGroup = function() {
        DefaultGroups.remove({realm: realm.realm, groupId: $scope.selectedGroup.id}, function() {
            refreshDefaultGroups();
            Notifications.success($translate.instant('group.default.remove.success'));
        });

    };

    var isLeaf = function(node) {
        return node.id !== "realm" && (!node.subGroups || node.subGroups.length === 0);
    };

    $scope.getGroupClass = function(node) {
        if (node.id === "realm") {
            return 'pficon pficon-users';
        }
        if(node.hasChild){
             return "collapsed";
        }

        if (isLeaf(node)) {
            return 'normal';
        }
        if (node.subGroups.length && node.collapsed) return 'collapsed';
        if (node.subGroups.length && !node.collapsed) return 'expanded';
        return 'collapsed';

    };

    $scope.getSelectedClass = function(node) {
        if (node.selected) {
            return 'selected';
        } else if ($scope.cutNode && $scope.cutNode.id === node.id) {
            return 'cut';
        }
        return undefined;
    }

    $scope.tree.selectNodeHead = function(node) {
            node.collapsed = !node.collapsed;

        	if ((!node.subGroups || !node.subGroups.length ) && node.id != '' && node.hasChild){
    			var queryParams = {
    				realm : realm.realm,
    				first : 0,
    				max : $scope.pageSize,
    				parent: node.id
    			};
        	     Groups.query(queryParams, function(entry) {
                       promiseSubGroups.resolve(entry);
                 }, function() {
                       promiseSubGroups.reject('subGroups Unable to fetch ' + queryParams);
                 });
                 var promiseSubGroups = $q.defer();
                 var promiseGetGroupsChain   = promiseSubGroups.promise.then(function(groups) {
                       console.log('*** subGroups call groups size: ' + groups.length);
                       if(groups && groups.length > 0){
                       		node.subGroups = groups;
                       		node.collapsed = false;
                       }
                 });

        	}
     };

});

module.controller('GroupBindUsersCtrl', function($scope, $route, $q, realm, Groups, GroupsCount, GroupMembership, UserGroupMapping, User, Notifications) {
    $scope.realm = realm;
    $scope.groupList = [];

    $scope.searchTerms = '';
    $scope.searchUserTerms = '';
    $scope.currentPage = 1;
    $scope.currentPageInput = $scope.currentPage;
    $scope.pageSize = 20;
    $scope.tree = [{
          "id" : "",
          "name": "Groups",
          "subGroups" : []
    }];

    $scope.query = {
        realm: realm.realm,
        max : $scope.pageSize,
        first : 0
    };

    var refreshGroups = function (search) {
        console.log('refreshGroups');

        var first = ($scope.currentPage * $scope.pageSize) - $scope.pageSize;
        console.log('first:' + first);
        var queryParams = {
            realm : realm.realm,
            first : first,
            max : $scope.pageSize
        };
        var countParams = {
            realm : realm.realm,
            top : 'true'
        };

        if(angular.isDefined(search) && search !== '') {
            queryParams.search = search;
            countParams.search = search;
        }
        var promiseGetGroups = $q.defer();
        Groups.query(queryParams, function(entry) {
            promiseGetGroups.resolve(entry);
        }, function() {
            promiseGetGroups.reject('Unable to fetch ' + queryParams);
        });
        var promiseGetGroupsChain   = promiseGetGroups.promise.then(function(groups) {
            console.log('*** group call groups size: ' + groups.length);
            $scope.groupList = [
                {
                    "id" : "",
                    "name": "Groups",
                    "subGroups" : groups
                }
            ];
        });

        var promiseCount = $q.defer();
        console.log('countParams: realm[' + countParams.realm);
        GroupsCount.query(countParams, function(entry) {
            promiseCount.resolve(entry);
        }, function() {
            promiseCount.reject('Unable to fetch ' + countParams);
        });
        var promiseCountChain   = promiseCount.promise.then(function(groupsCount) {
            $scope.numberOfPages = Math.ceil(groupsCount.count/$scope.pageSize);
         });
    };
    refreshGroups();

    $scope.$watch('currentPage', function(newValue, oldValue) {
        if(newValue !== oldValue) {
            refreshGroups($scope.searchTerms);
        }
    });

    $scope.clearSearch = function() {
        $scope.searchTerms = '';
        $scope.currentPage = 1;
        refreshGroups();
    };

    $scope.searchGroup = function() {
        $scope.currentPage = 1;
        refreshGroups($scope.searchTerms);
    };

    var isLeaf = function(node) {
        return node.id !== "realm" && (!node.subGroups || node.subGroups.length === 0);
    };

    $scope.getGroupClass = function(node) {
        if (node.id === "realm") {
            return 'pficon pficon-users';
        }
        if(node.hasChild){
            return 'collapsed';
        }
        if (isLeaf(node)) {
            return 'normal';
        }
        if (node.subGroups.length && node.collapsed) return 'collapsed';
        if (node.subGroups.length && !node.collapsed) return 'expanded';
        return 'collapsed';

    };

    $scope.getSelectedClass = function(node) {
        if (node.selected) {
            return 'selected';
        } else if ($scope.cutNode && $scope.cutNode.id === node.id) {
            return 'cut';
        }
        return undefined;
    }

    $scope.tree.selectNodeLabel = function(node) {
        $scope.query.groupId = node.id;
        if($scope.tree.currentNode && $scope.tree.currentNode.selected ) {
        	$scope.tree.currentNode.selected = undefined;
        }
        node.selected = 'selected';
        $scope.tree.currentNode = node;
        $scope.groupMembers = GroupMembership.query($scope.query, function() {
            console.log('user search loaded');
            $scope.searchLoaded = true;
            $scope.lastSearch = $scope.query.search;
        });
    }

    $scope.tree.selectNodeHead = function(node) {
            node.collapsed = !node.collapsed;

        	if ((!node.subGroups || !node.subGroups.length ) && node.id != '' && node.hasChild){
    			var queryParams = {
    				realm : realm.realm,
    				first : 0,
    				max : $scope.pageSize,
    				parent: node.id
    			};
        	     Groups.query(queryParams, function(entry) {
                       promiseSubGroups.resolve(entry);
                 }, function() {
                       promiseSubGroups.reject('subGroups Unable to fetch ' + queryParams);
                 });
                 var promiseSubGroups = $q.defer();
                 var promiseGetGroupsChain   = promiseSubGroups.promise.then(function(groups) {
                       console.log('*** subGroups call groups size: ' + groups.length);
                       if(groups && groups.length > 0){
                       		node.subGroups = groups;
                       		node.collapsed = false;
                       }
                 });

        	}
     };

    var getIndex = function(array, id) {
        var tmpItem = {};
        angular.forEach(array, function(item) {
          if (item.id == id) {
            tmpItem = item;
          }
        });
        return array.indexOf(tmpItem);
    }

	var containGroup = function(array, id) {
		var bool = false;
        angular.forEach(array, function(item) {
          if (item.id == id) {
			  bool = true;
              return false;
          }
        });
		return bool;
	}

    $scope.leaveGroupMember = function(){
        if (!$scope.selectedGroupMember || !$scope.tree.currentNode) {
            return;
        }
        UserGroupMapping.remove({realm: realm.realm, userId: $scope.selectedGroupMember, groupId: $scope.tree.currentNode.id}, function() {
			var tmpIdIndex = getIndex($scope.groupMembers, $scope.selectedGroupMember);
			if ($scope.selectedGroupMember[tmpIdIndex].showConfirm == false) {
				tmpIndex = realOptions.indexOf($scope.selectedGroupMember[tmpIdIndex].content);
				realOptions.splice(tmpIndex, 1);
			}
			$scope.groupMembers.splice(tmpIdIndex, 1);
            Notifications.success('Removed group member');
        });
    }


    $scope.joinUser = function() {
        if (!$scope.tree.currentNode) {
            Notifications.error('Please select a group');
            return;
        };

		if(containGroup($scope.groupMembers,$scope.selectedUser)==true){
			Notifications.error('The user is added, Please select other user to add');
            return;
		}
        var tmpIdIndex = getIndex($scope.users, $scope.selectedUser);
        var currentUser = $scope.users[tmpIdIndex];
        if(currentUser.groups && currentUser.groups.length > 0){
            $scope.query.search = currentUser.groups[0];
            Groups.query($scope.query, function(entry) {
                UserGroupMapping.remove({realm: realm.realm, userId: currentUser.id, groupId: entry[0].id});
            });
        }

        UserGroupMapping.update({realm: realm.realm, userId: $scope.selectedUser, groupId: $scope.tree.currentNode.id}, function() {
			$scope.groupMembers.push(currentUser);
            Notifications.success('Added group membership');
        });

    };

    $scope.firstPage = function() {
        $scope.query.first = 0;
        $scope.searchUser();
    }

    $scope.previousPage = function() {
        $scope.query.first -= parseInt($scope.query.max);
        if ($scope.query.first < 0) {
            $scope.query.first = 0;
        }
        $scope.searchUser();
    }

    $scope.nextPage = function() {
        $scope.query.first += parseInt($scope.query.max);
        $scope.searchUser();
    }

    $scope.searchUser = function() {
        $scope.query.search = $scope.searchUserTerms;
        console.log("query.search: " + $scope.query.search);
        $scope.searchLoaded = false;
        $scope.users = User.query($scope.query, function() {
            $scope.searchLoaded = true;
            $scope.lastSearch = $scope.query.search;
        });
    };


});

