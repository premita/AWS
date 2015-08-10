package com.icsynergy.processplugins;

import com.icsynergy.helpers.csf.CsfAccessor;
import oracle.bpel.services.workflow.client.IWorkflowServiceClient;
import oracle.bpel.services.workflow.client.IWorkflowServiceClientConstants;
import oracle.bpel.services.workflow.client.WorkflowServiceClientFactory;
import oracle.bpel.services.workflow.query.ITaskQueryService;
import oracle.bpel.services.workflow.repos.Ordering;
import oracle.bpel.services.workflow.repos.Predicate;
import oracle.bpel.services.workflow.repos.TableConstants;
import oracle.bpel.services.workflow.task.model.Task;
import oracle.bpel.services.workflow.verification.IWorkflowContext;
import oracle.iam.conf.api.SystemConfigurationService;
import oracle.iam.identity.exception.*;
import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.identity.usermgmt.vo.UserManagerResult;
import oracle.iam.platform.OIMClient;
import oracle.iam.platform.Platform;
import oracle.iam.platform.context.ContextAware;
import oracle.iam.platform.context.ContextManager;
import oracle.iam.platform.kernel.EventFailedException;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.*;
import oracle.security.jps.service.credstore.PasswordCredential;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SetManagerHandler implements PostProcessHandler {
    public static final String AWS_DELEGATED_ADMIN_DEFAULT = "aws_delegated_admin_default";
    public static final String OIMURL = "AWS.OIMURL";

    private ITaskQueryService querySvc = null;
    private IWorkflowContext wfCtx = null;
    private OIMClient oimClient = null;

    Logger log = Logger.getLogger("com.icsynergy");

    @Override
    public EventResult execute(long l, long l1, Orchestration orchestration) {
        log.finest(">> execute");

        if (querySvc == null) {
            log.finest("initializing...");
            init();
        }

        if (ContextManager.getContextType().toString().equals("REQUEST")) {
            log.finest("context is request");

            HashMap<String, ContextAware> reqData =
                    (HashMap<String, ContextAware>) ContextManager.getValue("requestData", true);

            String reqId = (String) reqData.get("requestId").getObjectValue();
            log.finest("Request Id: " + reqId);

            final String strApprover = getRequestApproverFromWF(reqId);
            log.finer("Approver is: " + strApprover);

            log.finest("getting approver key");
            UserManager userMgr = Platform.getService(UserManager.class);
            User approver;
            try {
                approver = userMgr.getDetails(strApprover, null, true);
            } catch (UserLookupException | NoSuchUserException e) {
                log.log(Level.SEVERE, "Exception", e);
                throw new EventFailedException(e.getMessage(), e.getErrorData(), e);
            }

            log.finest("getting role key");
            RoleManager roleMgr = Platform.getService(RoleManager.class);
            Role adminRole;
            try {
                adminRole = roleMgr.getDetails(RoleManagerConstants.RoleAttributeName.NAME.getId(), AWS_DELEGATED_ADMIN_DEFAULT, null);
            } catch (RoleLookupException | NoSuchRoleException | SearchKeyNotUniqueException e) {
                log.log(Level.SEVERE, "Exception", e);
                throw new EventFailedException(e.getMessage(), e.getErrorData(), e);
            }

            log.finest("checking if approver has the role of default delegated admin");
            try {
                String orgCertifierKey = approver.getEntityId();

                if (roleMgr.isRoleGranted(adminRole.getEntityId(), approver.getEntityId(), true)) {
                    log.finest("approver has the role, need to set user\'s manager to org certifier");

                    log.finest("getting target user details");
                    User targetUser = userMgr.getDetails(orchestration.getTarget().getEntityId(), null, false);
                    final String orgKey =
                            targetUser.getAttribute(UserManagerConstants.AttributeName.USER_ORGANIZATION.getId()).toString();
                    log.finest("user\'s orgKey: " + orgKey);

                    log.finest("getting org details");
                    OrganizationManager orgMgr = Platform.getService(OrganizationManager.class);
                    Organization org = orgMgr.getDetails(orgKey, null, false);

                    Object objOrgCertifier = org.getAttribute(OrganizationManagerConstants.AttributeName.ORG_CERTIFIER_USER_KEY.getId());
                    if (objOrgCertifier != null) {
                        orgCertifierKey = objOrgCertifier.toString();
                        log.finest("org certifier key: " + orgCertifierKey);
                    }
                }

                log.finest("setting user manager to 'org certifier'");
                User updatedUser = new User(orchestration.getTarget().getEntityId());
                updatedUser.setManagerKey(orgCertifierKey);

                try {
                    log.finest("pushing new context");
                    ContextManager.pushContext(reqId, ContextManager.ContextTypes.ADMIN, "MODIFY");

                    log.finest("modifying user with user");
                    UserManagerResult result = getOIMClient().getService(UserManager.class).modify(updatedUser);

                    if (result.getSucceededResults().contains(updatedUser.getEntityId())) {
                        log.info("User is successfully updated");
                    }
                } catch (UserModifyException | ValidationFailedException e) {
                    log.log(Level.SEVERE, "Exception", e);
                    throw new EventFailedException(e.getMessage(), e.getErrorData(), e);
                } finally {
                    log.finest("popping old context");
                    ContextManager.popContext();
                }
            } catch (OrganizationManagerException | UserLookupException | NoSuchUserException | UserMembershipException e) {
                log.log(Level.SEVERE, "Exception", e);
                throw new EventFailedException(e.getMessage(), e.getErrorData(), e);
            }
        }

        log.finest("<< execute");
        return new EventResult();
    }

    @Override
    public BulkEventResult execute(long l, long l1, BulkOrchestration bulkOrchestration) {
        return null;
    }

    @Override
    public void compensate(long l, long l1, AbstractGenericOrchestration abstractGenericOrchestration) {

    }

    @Override
    public boolean cancel(long l, long l1, AbstractGenericOrchestration abstractGenericOrchestration) {
        return false;
    }

    @Override
    public void initialize(HashMap<String, String> hashMap) {
    }

    private OIMClient getOIMClient() {
        log.finest(">> getOIMClient");

        // log into the system
        try {
            log.finest("getting credentials");
            PasswordCredential creds = CsfAccessor.readCredentialsfromCsf("oim", "sysadmin");

            String strOimUserName = creds.getName();
            String strOimPassword = new String(creds.getPassword());

            log.finest("getting OIM URL");
            SystemConfigurationService cfgServ = Platform.getService(SystemConfigurationService.class);
            final String strURL = cfgServ.getSystemPropertiesForUnauthenticatedUsers(OIMURL).getPtyValue();
            log.finest("url: " + strURL);

            log.finest("prepping environment");
            Hashtable env = new Hashtable();
            env.put(OIMClient.JAVA_NAMING_FACTORY_INITIAL, "weblogic.jndi.WLInitialContextFactory");
            env.put(OIMClient.JAVA_NAMING_PROVIDER_URL, strURL);

            log.finest("getting OIMClient");
            oimClient = new OIMClient(env);

            log.finest("Logging in...");
            oimClient.login(strOimUserName, strOimPassword.toCharArray());
            log.finest("Logged in");
        } catch (Exception e) {
            log.log(Level.SEVERE, "Can't log in", e);
        }


        log.finest("<< getOIMClient");
        return oimClient;
    }

    public void init() {
        try {
            log.finest(">> init");

            log.finest("getting initial context...");
            InitialContext ctx = new InitialContext();

            log.finest("looking up MBeanServer interface...");
            MBeanServer server = (MBeanServer) ctx.lookup("java:comp/env/jmx/runtime");

            ObjectName objName = new ObjectName("oracle.iam:name=SOAConfig,type=XMLConfig.SOAConfig,XMLConfig=Config," + "Application=oim,ApplicationVersion=11.1.2.0.0");

            log.finest("getting MBean attributes...");
            String rmiURL = (String) server.getAttribute(objName, "Rmiurl");
            String passKey = (String) server.getAttribute(objName, "PasswordKey");
            log.finest("rmiurl=" + rmiURL + " passKey=" + passKey);

            log.finest("getting credentials from the key store...");
            PasswordCredential creds = CsfAccessor.readCredentialsfromCsf("oim", passKey);

            String strSOAUserName = creds.getName();
            String strSOAPassword = new String(creds.getPassword());

            Map<IWorkflowServiceClientConstants.CONNECTION_PROPERTY, String> properties = new HashMap<>();
            properties.put(IWorkflowServiceClientConstants.CONNECTION_PROPERTY.CLIENT_TYPE, WorkflowServiceClientFactory.REMOTE_CLIENT);
            properties.put(IWorkflowServiceClientConstants.CONNECTION_PROPERTY.EJB_PROVIDER_URL, rmiURL);

            log.finest("getting workflow service client...");
            IWorkflowServiceClient client = WorkflowServiceClientFactory.getWorkflowServiceClient(properties, null);

            querySvc = client.getTaskQueryService();
            log.finest("authenticating ...");
            wfCtx = querySvc.authenticate(strSOAUserName, strSOAPassword.toCharArray(), "jazn.com");

            log.finest("<< init");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.log(Level.SEVERE, "Exception!", e);
        }

    }

    /**
     * Retrieves from WorkflowInterface a request approver for a given request id
     *
     * @param strReqId Request to search
     * @return strLogin on success, empty string otherwise
     */
    private String getRequestApproverFromWF(final String strReqId) {
        log.finest(">> getRequestApproverFromWF(" + strReqId + ")");

        String strRet = "";

        try {
            log.finest("building predicate");
            // Build the predicate
            Predicate idPredicate = new Predicate(TableConstants.WFTASK_IDENTIFICATIONKEY_COLUMN, Predicate.OP_EQ, strReqId);
            idPredicate.addClause(Predicate.AND, TableConstants.WFTASK_OUTCOME_COLUMN, Predicate.OP_EQ, "APPROVE");
            idPredicate.addClause(Predicate.AND, TableConstants.WFTASK_HASSUBTASK_COLUMN, Predicate.OP_NEQ, "T");

            // Create the ordering
            log.finest("creating the ordering");
            Ordering ordering = new Ordering(TableConstants.WFTASK_TITLE_COLUMN, true, true);
            ordering.addClause(TableConstants.WFTASK_PRIORITY_COLUMN, true, true);

            List queryColumns = new ArrayList();
            queryColumns.add("APPROVERS");

            log.finest("querying...");
            List tasksList = querySvc.queryTasks(wfCtx, queryColumns, null, ITaskQueryService.AssignmentFilter.ALL, null, idPredicate, ordering, 0, 0);// No Paging

            if (tasksList != null) {
                if (tasksList.size() > 1) {
                    log.warning("More that one task found with ID: " + strReqId + "returning null...");
                } else {
                    Task task = (Task) tasksList.get(0);
                    strRet = task.getSystemAttributes().getApprovers();
                    log.finest("Approvers: " + strRet);
                }

            } else {
                log.warning("No task found with ID: " + strReqId + " returning null...");
            }

        } catch (Exception e) {
            log.log(Level.SEVERE, "Exception", e);
        }


        log.finest("<< getRequestApproverFromWF with " + strRet);
        return strRet;
    }

}
