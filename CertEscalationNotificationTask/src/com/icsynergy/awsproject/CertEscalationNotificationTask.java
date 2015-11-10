package com.icsynergy.awsproject;

import com.icsynergy.helpers.csf.CsfAccessor;
import oracle.bpel.services.workflow.WorkflowException;
import oracle.bpel.services.workflow.client.IWorkflowServiceClient;
import oracle.bpel.services.workflow.client.IWorkflowServiceClientConstants;
import oracle.bpel.services.workflow.client.WorkflowServiceClientFactory;
import oracle.bpel.services.workflow.query.ITaskQueryService;
import oracle.bpel.services.workflow.query.ejb.TaskQueryServiceBean;
import oracle.bpel.services.workflow.repos.Predicate;
import oracle.bpel.services.workflow.repos.Ordering;
import oracle.bpel.services.workflow.repos.TableConstants;
import oracle.bpel.services.workflow.task.model.Task;
import oracle.bpel.services.workflow.verification.IWorkflowContext;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.Platform;
import oracle.iam.platform.kernel.EventFailedException;
import oracle.iam.scheduler.vo.TaskSupport;
import oracle.security.jps.service.credstore.PasswordCredential;

import javax.management.*;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CertEscalationNotificationTask extends TaskSupport {
    Logger log = Logger.getLogger(this.getClass().getCanonicalName());

    IWorkflowContext wfCtx;
    ITaskQueryService querySvc;

    @Override
    public void execute(HashMap hashMap) throws Exception {

        log.entering(this.getClass().getName(), "execute");

        init();

        UserManager usrMgr = Platform.getService(UserManager.class);

        List<Task> lstTask = getActiveCertifications();
        for (Task task: lstTask) {
            if (isStop()) {
                log.fine("job is stopped, exiting...");
                return;
            }
            // check if now > half task length
           if ((task.getSystemAttributes().getExpirationDate().getTimeInMillis()
                   - task.getSystemAttributes().getAssignedDate().getTimeInMillis())/2 <= new Date().getTime()) {
               String strAssignee = String.valueOf(task.getSystemAttributes().getAssignees().get(0));
               log.finest("task assignee: " + strAssignee);

               log.finest("getting user details...");
               User usr = usrMgr.getDetails(strAssignee, null, true);
               log.finest("user's manager key: " + usr.getManagerKey());

               log.finest("getting manager details...");
               User mgr = usrMgr.getDetails(usr.getManagerKey(), null, false);
               log.finest("manager login: " + mgr.getLogin());

               sendEscalationNotification(mgr);
           }
        }


        log.exiting(this.getClass().getName(), "execute");
    }

    private void sendEscalationNotification(User mgr) {
        log.finest(">> sendEscalationNotification: " + mgr.getLogin());
        log.finest("<< sendEscalationNotification: " + mgr.getLogin());
    }

    @Override
    public HashMap getAttributes() {
        return null;
    }

    @Override
    public void setAttributes() {

    }

    private void init() {
        try {
            log.finest(">> init");

            log.finest("getting initial context...");
            InitialContext ctx = new InitialContext();

            log.finest("looking up MBeanServer interface...");
            MBeanServer server = (MBeanServer) ctx.lookup("java:comp/env/jmx/runtime");

            log.finest("getting task query service bean");
            TaskQueryServiceBean bean = (TaskQueryServiceBean) ctx.lookup("ejb/bpel/services/workflow/TaskQueryService");

            ObjectName objName = new ObjectName("oracle.iam:name=SOAConfig,type=XMLConfig.SOAConfig,XMLConfig=Config," + "Application=oim,ApplicationVersion=11.1.2.0.0");

            log.finest("getting MBean attributes...");
            String rmiURL = (String) server.getAttribute(objName, "Rmiurl");
            String passKey = (String) server.getAttribute(objName, "PasswordKey");
            log.finest("rmiurl=" + rmiURL + " passKey=" + passKey);

            log.finest("getting credentials from the key store...");
            PasswordCredential creds = CsfAccessor.readCredentialsfromCsf("oim", passKey);

            String strSOAUserName = creds.getName();
            String strSOAPassword = new String(creds.getPassword());

            log.finest("authenticating through bean");
            wfCtx = bean.authenticate(strSOAUserName, strSOAPassword.toCharArray(), "jazn.com");

            Map<IWorkflowServiceClientConstants.CONNECTION_PROPERTY, String> properties = new HashMap<>();
            properties.put(IWorkflowServiceClientConstants.CONNECTION_PROPERTY.CLIENT_TYPE, WorkflowServiceClientFactory.REMOTE_CLIENT);
            properties.put(IWorkflowServiceClientConstants.CONNECTION_PROPERTY.EJB_PROVIDER_URL, rmiURL);

            log.finest("getting workflow service client...");
            IWorkflowServiceClient client = WorkflowServiceClientFactory.getWorkflowServiceClient(properties, null);

            querySvc = client.getTaskQueryService();
            log.finest("authenticating ...");
            wfCtx = querySvc.authenticate(strSOAUserName, strSOAPassword.toCharArray(), "jazn.com");

            log.finest("<< init");
        } catch (MalformedObjectNameException | WorkflowException | MBeanException | AttributeNotFoundException
                | ReflectionException |InstanceNotFoundException | NamingException e) {
            log.log(Level.SEVERE, "Exception!", e);
            throw new EventFailedException(e.getMessage(), e.getStackTrace(), e.getCause());
        }
    }

    /**
     * Retrieves from WorkflowInterface a request approver for a given request id
     *
     * @return strLogin on success, empty string otherwise
     */
    private List<Task> getActiveCertifications() {
        log.finest(">> getActiveCertifications");

        try {

            log.finest("building predicate");
            // Build the predicate
            Predicate idPredicate = new Predicate(TableConstants.WFTASK_CATEGORY_COLUMN, Predicate.OP_EQ, "certification");
            idPredicate.addClause(Predicate.AND, TableConstants.WFTASK_STATE_COLUMN, Predicate.OP_EQ, "ASSIGNED");

            // Create the ordering
            log.finest("creating the ordering");
            Ordering ordering = new Ordering(TableConstants.WFTASK_TITLE_COLUMN, true, true);
            ordering.addClause(TableConstants.WFTASK_PRIORITY_COLUMN, true, true);

            log.finest("adding result columns into the list");
            List<String> queryColumns = new ArrayList<>();
            queryColumns.add(TableConstants.WFTASK_ASSIGNEDDATE_COLUMN.getName());
            queryColumns.add(TableConstants.WFTASK_EXPIRATIONDATE_COLUMN.getName());
            queryColumns.add(TableConstants.WFTASK_ASSIGNEES_COLUMN.getName());
            log.finest("list: " + queryColumns.toString());

            log.finest("querying and exiting...");
            return querySvc.queryTasks(
                            wfCtx, queryColumns, null, ITaskQueryService.AssignmentFilter.ALL,
                            null, idPredicate, ordering, 0, 0);// No Paging

        } catch (WorkflowException e) {
            log.log(Level.SEVERE, "Exception", e);
            throw new EventFailedException(e.getMessage(), e.getErrorArgs(), e.getCause());
        }
    }
}
