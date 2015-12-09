package com.icsynergy.awsproject;

import com.icsynergy.helpers.csf.CsfAccessor;
import oracle.bpel.services.workflow.WorkflowException;
import oracle.bpel.services.workflow.client.IWorkflowServiceClient;
import oracle.bpel.services.workflow.client.IWorkflowServiceClientConstants;
import oracle.bpel.services.workflow.client.WorkflowServiceClientFactory;
import oracle.bpel.services.workflow.query.ITaskQueryService;
import oracle.bpel.services.workflow.repos.Ordering;
import oracle.bpel.services.workflow.repos.Predicate;
import oracle.bpel.services.workflow.repos.TableConstants;
import oracle.bpel.services.workflow.task.model.IdentityType;
import oracle.bpel.services.workflow.task.model.Task;
import oracle.bpel.services.workflow.verification.IWorkflowContext;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.notification.api.NotificationService;
import oracle.iam.notification.exception.*;
import oracle.iam.notification.vo.NotificationEvent;
import oracle.iam.platform.Platform;
import oracle.iam.platform.kernel.EventFailedException;
import oracle.iam.scheduler.vo.TaskSupport;
import oracle.security.jps.service.credstore.PasswordCredential;

import javax.management.*;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CertReminderNotificationTask extends TaskSupport {
    Logger log = Logger.getLogger(this.getClass().getCanonicalName());

    IWorkflowContext wfCtx;
    ITaskQueryService querySvc;
    NotificationService notificationService = Platform.getService(NotificationService.class);

    String strNotificationTemplateName;

    @Override
    public void execute(HashMap hashMap) throws Exception {
        log.entering(this.getClass().getName(), "execute");

        log.finer("checking task parameters...");

        strNotificationTemplateName = String.valueOf(hashMap.get("Template Name"));
        log.finest("notification template parameter: " + strNotificationTemplateName);

        if (strNotificationTemplateName.isEmpty()) {
            throw new EventFailedException("Notification template name is empty");
        }

        int iDays = Integer.valueOf(String.valueOf(hashMap.get("Days Since Start")));
        log.finest("days since assignment parameter: " + iDays);

        log.finer("initiating task query service");
        init();

        UserManager usrMgr = Platform.getService(UserManager.class);

        log.finest("getting running certifications...");
        List<Task> lstTask = getActiveCertifications();

        log.finest("iterating through the list of active certifications...");

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

        for (Task task: lstTask) {
            log.finest("processing task: " + task.getIdentificationKey());

            log.finest("checking if we're stopped");
            if (isStop()) {
                log.fine("job is stopped, exiting...");
                return;
            }

            log.finer("checking task dates...");

            Calendar calAssigned = task.getSystemAttributes().getAssignedDate();
            log.finest("start: " + dateFormat.format(calAssigned.getTime()));

            log.finest("now: " + dateFormat.format(new Date()));

            // redefine calAssigned as calAssigned + iDays
            calAssigned.add(Calendar.DAY_OF_MONTH, iDays);
            log.finest("date of notification: " + dateFormat.format(calAssigned.getTime()));

            if (compare(Calendar.getInstance(), calAssigned) == 0) {
                log.finest("now is the notification date");

                IdentityType identity = (IdentityType) task.getSystemAttributes().getAssignees().get(0);
                String strAssignee = identity.getId();
                log.finest("task assignee: " + strAssignee);

                log.finest("getting user details...");
                User usr = usrMgr.getDetails(strAssignee, null, true);
                log.finest("user's manager key: " + usr.getManagerKey());

                sendEscalationNotification(usr, task.getSystemAttributes().getExpirationDate());
            }
        }

        log.exiting(this.getClass().getName(), "execute");
    }

    private void sendEscalationNotification(User assignee, Calendar expDate) {
        log.finest(">> sendEscalationNotification: " + assignee.getLogin());

        NotificationEvent event = new NotificationEvent();

        log.finest("setting recepient");
        String[] arTo = { assignee.getLogin() };
        event.setUserIds(arTo);

        event.setTemplateName(strNotificationTemplateName);

        log.finest("prepping event map");
        HashMap<String, Object> map = new HashMap<>();
        map.put("emp_disp_name", assignee.getDisplayName());
        map.put("exp_date", expDate.getTime().toString());
        //fake one
        map.put("act_key", 1);
        event.setParams(map);

        try {
            notificationService.notify(event);
            log.fine("escalation notification sent");
        } catch (NotificationException | UserDetailsNotFoundException | NotificationResolverNotFoundException |
                MultipleTemplateException | TemplateNotFoundException | UnresolvedNotificationDataException |
                EventException e) {
            log.severe("Exception sending notification: " + e.getMessage());
        }

        log.finest("<< sendEscalationNotification: " + assignee.getLogin());
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
            queryColumns.add(TableConstants.WFTASK_IDENTIFICATIONKEY_COLUMN.getName());
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

    private int compare(Calendar c1, Calendar c2) {
        if (c1.get(Calendar.YEAR) != c2.get(Calendar.YEAR))
            return c1.get(Calendar.YEAR) - c2.get(Calendar.YEAR);

        if (c1.get(Calendar.MONTH) != c2.get(Calendar.MONTH))
            return c1.get(Calendar.MONTH) - c2.get(Calendar.MONTH);

        return c1.get(Calendar.DAY_OF_MONTH) - c2.get(Calendar.DAY_OF_MONTH);
    }
}
