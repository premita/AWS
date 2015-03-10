package com.icsynergy.processplugins;

import com.icsynergy.helpers.csf.CsfAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import javax.naming.Context;
import javax.naming.InitialContext;

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
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.OIMClient;
import oracle.iam.platform.Platform;
import oracle.iam.platform.context.ContextAware;
import oracle.iam.platform.context.ContextManager;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;


import oracle.security.jps.service.credstore.PasswordCredential;


public class CreateUserSetManagerHandler implements PostProcessHandler {
  private final static Logger logger = Logger.getLogger("com.icsynergy");
  private final static String TAG =
    CreateUserSetManagerHandler.class.getCanonicalName();

  public CreateUserSetManagerHandler() {
    super();
  }

  public EventResult execute(long l, long l2, Orchestration orch) {
    final String OIMURL = "AWS.OIMURL";
    logger.entering(TAG, "execute");

    logger.finest("Orig user: " + ContextManager.getOrigUser());
    logger.finest("All values: " +
                  ContextManager.getAllValuesFromCurrentContext().toString());

    HashMap<String, ContextAware> requestContext =
      (HashMap<String, ContextAware>)ContextManager.getValue("requestData",
                                                             true);

    if (requestContext != null) {
      ContextAware ctxawReqId = requestContext.get("requestId");
      String strReqId = (String) ctxawReqId.getObjectValue();
      logger.finest("reqId: " + strReqId);

      String strApproverLogin = getRequestApproverFromWF(strReqId);
      if (strApproverLogin != null) {
/*      
        // log into the system    
        OIMClient oimClient = null;  
        
        try {
          logger.finest("Logging in...");
          CsfAccessor credReader = new CsfAccessor();
          PasswordCredential creds = credReader.readCredentialsfromCsf("oim", "sysadmin");
            
          String strOimUserName = creds.getName();
          String strOimPassword = new String(creds.getPassword());

          SystemConfigurationService cfgServ = 
            Platform.getService(SystemConfigurationService.class);
          String strURL =
            cfgServ.getSystemPropertiesForUnauthenticatedUsers(OIMURL)
            .getPtyValue();
          
          Hashtable env = new Hashtable();
          env.put(OIMClient.JAVA_NAMING_FACTORY_INITIAL, "weblogic.jndi.WLInitialContextFactory");
          env.put(OIMClient.JAVA_NAMING_PROVIDER_URL, strURL);
          oimClient = new OIMClient(env);    
          
          oimClient.login(strOimUserName, strOimPassword.toCharArray());
          logger.finest("Logged in");
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Can't log in", e);
          return new EventResult();
        }
        UserManager usrMgr = oimClient.getService(UserManager.class);
*/
        ContextManager.pushContext(orch.getTarget().getEntityId(), 
                                   ContextManager.ContextTypes.ADMIN,
                                   "Update User");
        UserManager usrMgr = 
          Platform.getService(UserManager.class);
/*          Platform.getServiceForEventHandlers(UserManager.class, 
                                              "Admin Update " + strReqId, 
                                              ContextManager.ContextTypes.ADMIN.toString(),
                                              null, null);
*/        
        try {
          // find manager by login
          User mgr =
              usrMgr.getDetails(UserManagerConstants.AttributeName.USER_LOGIN.getId(),
                            strApproverLogin, null);
          
          User usr = new User(orch.getTarget().getEntityId());
          
          usr.setManagerKey(mgr.getEntityId());
          usrMgr.modify(usr);
        } catch (Exception e) {
          logger.log(Level.SEVERE,"Exception", e);
        }
      } else {
        logger.warning("Can't find an approver for request ID: " + strReqId);
      }
    }

    logger.exiting(TAG, "execute");
    return new EventResult();
  }

  public BulkEventResult execute(long l, long l2,
                                 BulkOrchestration bulkOrchestration) {
    return null;
  }

  public void compensate(long l, long l2,
                         AbstractGenericOrchestration abstractGenericOrchestration) {
  }

  public boolean cancel(long l, long l2,
                        AbstractGenericOrchestration abstractGenericOrchestration) {
    return false;
  }

  public void initialize(HashMap<String, String> hashMap) {
  }

  /**
   * Retrieves from WorkflowInterface a request approver for a given request id
   * @param strReqId Request to search 
   * @return strLogin on success, null otherwise
   */
  private String getRequestApproverFromWF(String strReqId) {
    logger.entering(TAG, "getRequestApproverFromWF", strReqId);

    String strRet = null;

    try {
      logger.finest("getting initial context...");
      InitialContext ctx = new InitialContext();

      logger.finest("looking up MBeanServer interface...");
      MBeanServer server =
        (MBeanServer)ctx.lookup("java:comp/env/jmx/runtime");

      ObjectName objName =
        new ObjectName("oracle.iam:name=SOAConfig,type=XMLConfig.SOAConfig,XMLConfig=Config," +
                       "Application=oim,ApplicationVersion=11.1.2.0.0");

      logger.finest("getting MBean attributes...");
      String rmiURL = (String)server.getAttribute(objName, "Rmiurl");
      String passKey = (String)server.getAttribute(objName, "PasswordKey");
      logger.finest("rmiurl=" + rmiURL + " passKey=" + passKey);

      logger.finest("getting credentials from the key store...");
      CsfAccessor credReader = new CsfAccessor();
      PasswordCredential creds =
        credReader.readCredentialsfromCsf("oim", passKey);

      String strSOAUserName = creds.getName();
      String strSOAPassword = new String(creds.getPassword());

      Map<IWorkflowServiceClientConstants.CONNECTION_PROPERTY, String> properties =
        new HashMap<>();
      properties.put(IWorkflowServiceClientConstants.CONNECTION_PROPERTY.CLIENT_TYPE,
                     WorkflowServiceClientFactory.REMOTE_CLIENT);
      properties.put(IWorkflowServiceClientConstants.CONNECTION_PROPERTY.EJB_PROVIDER_URL,
                     rmiURL);

      logger.finest("getting workflow service client...");
      IWorkflowServiceClient client =
        WorkflowServiceClientFactory.getWorkflowServiceClient(properties,
                                                              null);

      ITaskQueryService querySvc = client.getTaskQueryService();
      logger.finest("authenticating ...");
      IWorkflowContext wfCtx =
        querySvc.authenticate(strSOAUserName, strSOAPassword.toCharArray(),
                              "jazn.com");

      // Build the predicate
      Predicate idPredicate =
        new Predicate(TableConstants.WFTASK_IDENTIFICATIONKEY_COLUMN,
                      Predicate.OP_EQ, strReqId);
      idPredicate.addClause(Predicate.AND,
                            TableConstants.WFTASK_OUTCOME_COLUMN,
                            Predicate.OP_EQ, "APPROVE");
      idPredicate.addClause(Predicate.AND,
                            TableConstants.WFTASK_HASSUBTASK_COLUMN,
                            Predicate.OP_NEQ, "T");

      // Create the ordering
      Ordering ordering =
        new Ordering(TableConstants.WFTASK_TITLE_COLUMN, true, true);
      ordering.addClause(TableConstants.WFTASK_PRIORITY_COLUMN, true, true);

      List queryColumns = new ArrayList();
      queryColumns.add("APPROVERS");

      logger.finest("querying...");
      List tasksList =
        querySvc.queryTasks(wfCtx, queryColumns, null, ITaskQueryService.AssignmentFilter.ALL,
                            null, idPredicate, ordering, 0, 0); // No Paging

      if (tasksList != null) {
        if (tasksList.size() > 1) {
          logger.warning("More that one task found with ID: " + strReqId +
                         "returning null...");
        } else {
          Task task = (Task)tasksList.get(0);
          strRet = task.getSystemAttributes().getApprovers();
          logger.finest("Approvers: " + strRet);
        }
      } else {
        logger.warning("No task found with ID: " + strReqId +
                       " returning null...");
      }
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Exception", e);
    }

    logger.exiting(TAG, "getRequestApproverFromWF", strRet);
    return strRet;
  }
}
