/*
 * Copyright (c) 2022, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.organization.management.application.listener;

import org.apache.commons.lang.StringUtils;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.application.mgt.listener.AbstractApplicationMgtListener;
import org.wso2.carbon.identity.application.mgt.listener.ApplicationMgtListener;
import org.wso2.carbon.identity.core.model.IdentityEventListenerConfig;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.organization.management.application.dao.OrgApplicationMgtDAO;
import org.wso2.carbon.identity.organization.management.application.internal.OrgApplicationMgtDataHolder;
import org.wso2.carbon.identity.organization.management.application.model.MainApplicationDO;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;

import java.util.Arrays;
import java.util.Optional;

import static java.lang.String.format;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.DELETE_FRAGMENT_APPLICATION;
import static org.wso2.carbon.identity.organization.management.application.constant.OrgApplicationMgtConstants.IS_FRAGMENT_APP;

/**
 * Application listener to restrict actions on shared applications and fragment applications.
 */
public class FragmentApplicationMgtListener extends AbstractApplicationMgtListener {

    @Override
    public int getDefaultOrderId() {

        return 50;
    }

    @Override
    public boolean isEnable() {

        IdentityEventListenerConfig identityEventListenerConfig = IdentityUtil.readEventListenerProperty
                (ApplicationMgtListener.class.getName(), this.getClass().getName());

        if (identityEventListenerConfig == null) {
            return false;
        }

        if (StringUtils.isNotBlank(identityEventListenerConfig.getEnable())) {
            return Boolean.parseBoolean(identityEventListenerConfig.getEnable());
        }
        return false;

    }

    @Override
    public boolean doPreUpdateApplication(ServiceProvider serviceProvider, String tenantDomain,
                                          String userName) throws IdentityApplicationManagementException {

        // If the application is a fragment application, only certain configurations are allowed to be updated since
        // the organization login authenticator needs some configurations unchanged. Hence, the listener will override
        // any configs changes that are required for organization login.
        ServiceProvider existingApplication =
                getApplicationByResourceId(serviceProvider.getApplicationResourceId(), tenantDomain);
        if (existingApplication != null && Arrays.stream(existingApplication.getSpProperties())
                .anyMatch(p -> IS_FRAGMENT_APP.equalsIgnoreCase(p.getName()) && Boolean.parseBoolean(p.getValue()))) {
            serviceProvider.setSpProperties(existingApplication.getSpProperties());
            serviceProvider.setInboundAuthenticationConfig(existingApplication.getInboundAuthenticationConfig());
        }

        return super.doPreUpdateApplication(serviceProvider, tenantDomain, userName);
    }

    @Override
    public boolean doPostGetServiceProvider(ServiceProvider serviceProvider, String applicationName,
                                            String tenantDomain) throws IdentityApplicationManagementException {

        // If the application is a shared application, updates to the application are allowed
        if (Arrays.stream(serviceProvider.getSpProperties())
                .anyMatch(p -> IS_FRAGMENT_APP.equalsIgnoreCase(p.getName()) && Boolean.parseBoolean(p.getValue()))) {
            Optional<MainApplicationDO> mainApplicationDO;
            try {
                String sharedOrgId = getOrganizationManager().resolveOrganizationId(tenantDomain);
                mainApplicationDO = getOrgApplicationMgtDAO()
                        .getMainApplication(serviceProvider.getApplicationResourceId(), sharedOrgId);

                if (mainApplicationDO.isPresent()) {
                    /* Add User Attribute Section related configurations from the
                    main application to the shared application
                     */
                    String mainApplicationTenantDomain = getOrganizationManager()
                            .resolveTenantDomain(mainApplicationDO.get().getOrganizationId());
                    ServiceProvider mainApplication = getApplicationByResourceId
                            (mainApplicationDO.get().getMainApplicationId(), mainApplicationTenantDomain);
                    serviceProvider.setClaimConfig(mainApplication.getClaimConfig());
                    if (serviceProvider.getLocalAndOutBoundAuthenticationConfig() != null
                            && mainApplication.getLocalAndOutBoundAuthenticationConfig() != null) {
                        serviceProvider.getLocalAndOutBoundAuthenticationConfig()
                                .setUseTenantDomainInLocalSubjectIdentifier(mainApplication
                                        .getLocalAndOutBoundAuthenticationConfig()
                                        .isUseTenantDomainInLocalSubjectIdentifier());
                        serviceProvider.getLocalAndOutBoundAuthenticationConfig()
                                .setUseUserstoreDomainInLocalSubjectIdentifier(mainApplication
                                        .getLocalAndOutBoundAuthenticationConfig()
                                        .isUseUserstoreDomainInLocalSubjectIdentifier());
                        serviceProvider.getLocalAndOutBoundAuthenticationConfig()
                                .setUseUserstoreDomainInRoles(mainApplication
                                        .getLocalAndOutBoundAuthenticationConfig().isUseUserstoreDomainInRoles());
                    }
                }
            } catch (OrganizationManagementException e) {
                throw new IdentityApplicationManagementException
                        ("Error while retrieving the fragment application details.", e);
            }
        }

        return super.doPostGetServiceProvider(serviceProvider, applicationName, tenantDomain);
    }

    @Override
    public boolean doPreDeleteApplication(String applicationName, String tenantDomain, String userName)
            throws IdentityApplicationManagementException {

        ServiceProvider application = getApplicationByName(applicationName, tenantDomain);
        if (application == null) {
            return false;
        }

        // If the application is a fragment application, application cannot be deleted
        if (Arrays.stream(application.getSpProperties())
                .anyMatch(p -> IS_FRAGMENT_APP.equalsIgnoreCase(p.getName()) && Boolean.parseBoolean(p.getValue()))) {
            if (IdentityUtil.threadLocalProperties.get().containsKey(DELETE_FRAGMENT_APPLICATION)) {
                return true;
            }
            throw new IdentityApplicationManagementException(
                    format("Application with resource id: %s is a fragment application, hence the application cannot " +
                            "be deleted.", application.getApplicationResourceId()));
        }
        try {
            // If an application has fragment applications, application cannot be deleted.
            if (getOrgApplicationMgtDAO().hasFragments(application.getApplicationResourceId())) {
                throw new IdentityApplicationManagementException(
                        format("Application with resource id: %s is shared to other organization(s), hence the " +
                                "application cannot be deleted.", application.getApplicationResourceId()));
            }
        } catch (OrganizationManagementException e) {
            throw new IdentityApplicationManagementException("Error in validating the application for deletion.", e);
        }

        return super.doPreDeleteApplication(applicationName, tenantDomain, userName);
    }

    private ServiceProvider getApplicationByResourceId(String applicationResourceId, String tenantDomain)
            throws IdentityApplicationManagementException {

        return getApplicationMgtService().getApplicationByResourceId(applicationResourceId, tenantDomain);
    }

    private ServiceProvider getApplicationByName(String name, String tenantDomain)
            throws IdentityApplicationManagementException {

        return getApplicationMgtService().getServiceProvider(name, tenantDomain);
    }

    private ApplicationManagementService getApplicationMgtService() {

        return OrgApplicationMgtDataHolder.getInstance().getApplicationManagementService();
    }

    private OrgApplicationMgtDAO getOrgApplicationMgtDAO() {

        return OrgApplicationMgtDataHolder.getInstance().getOrgApplicationMgtDAO();
    }

    private OrganizationManager getOrganizationManager() {

        return OrgApplicationMgtDataHolder.getInstance().getOrganizationManager();
    }
}
