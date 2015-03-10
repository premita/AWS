package com.icsynergy.awsproject;


import com.icsynergy.helpers.csf.CsfAccessor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;

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

import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.notification.api.NotificationService;
import oracle.iam.notification.vo.NotificationEvent;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.scheduler.vo.TaskSupport;

import oracle.security.jps.service.credstore.PasswordCredential;


public class AssignManagerFromWFApprovalTask extends TaskSupport {
  private final Logger logger = Logger.getLogger("com.icsynergy");
  private final String TAG = this.getClass().getCanonicalName();
  
  private ITaskQueryService querySvc = null;
  private IWorkflowContext wfCtx = null;
  private Connection connDB = null;

  public AssignManagerFromWFApprovalTask() {
    super();

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
      PasswordCredential creds =
        CsfAccessor.readCredentialsfromCsf("oim", passKey);

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

      querySvc = client.getTaskQueryService();
      logger.finest("authenticating ...");
      wfCtx =
        querySvc.authenticate(strSOAUserName, strSOAPassword.toCharArray(),
                              "jazn.com");
      
      connDB = Platform.getOperationalDS().getConnection();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Exception!", e);
    }
  }

  public void execute(HashMap mapParams) throws Exception {
    logger.entering(TAG, "execute", mapParams);
    
    if (connDB == null || querySvc == null || wfCtx == null) {
      logger.severe("Initialization failed. Exiting");  
      return;
    }

    // check required params
    if (mapParams.get("Template Name").toString().isEmpty() ||
      mapParams.get("User to Notify").toString().isEmpty())
      throw new Exception("Required parameters are missing");
    
    String strTemplateName = mapParams.get("Template Name").toString();
    String strUserToNotify = mapParams.get("User to Notify").toString();

    NotificationService notifSrv = 
      Platform.getService(NotificationService.class);
    // try to lookup the template given -> throws execption if fails
    notifSrv.lookupTemplate(strTemplateName, Locale.getDefault());
    
    logger.finest("Getting User Manager service");
    UserManager usrMgr = Platform.getService(UserManager.class);
    
    // check if user exists -> throw an exception otherwise
    usrMgr.getDetails(strUserToNotify, null, true);
    
    // looking for active users only
    SearchCriteria critActive = 
      new SearchCriteria(UserManagerConstants.AttributeName.STATUS.getId(),
                         UserManagerConstants.AttributeValues.USER_STATUS_ACTIVE.getId(),
                         SearchCriteria.Operator.EQUAL);
    // no manager assigned
    SearchCriteria critNoMgr =
      new SearchCriteria(UserManagerConstants.AttributeName.MANAGER_KEY.getId(),
                         null,
                         SearchCriteria.Operator.EQUAL);
    SearchCriteria crit = 
      new SearchCriteria(critActive, critNoMgr, SearchCriteria.Operator.AND);
    
    logger.finest("Searching for users with specified criterias...");
    List<User> lstUsers =
      usrMgr.search(crit, null, null);
    
    // list of logins without managers
    ArrayList<String> arLogins = new ArrayList<>();
    logger.finest("Iterating...");
    for (User usr : lstUsers) {

      if (this.isStop()) {
        logger.info("Task has been stopped");
        return;
      }      

      switch (usr.getLogin().toUpperCase(Locale.getDefault())) {
      case "XELSYSADM" :
      case "OIMINTERNAL" :
      case "WEBLOGIC" :
        break;
      default:
        // add to the list, if successfully assigned -> will be deleted after
        arLogins.add(usr.getLogin());
        
        logger.finest("searching a request for a user...: " + usr.getLogin());
        Long lReqKey = getRequestNumberForUser(usr.getLogin());
        logger.info("Request key: " + lReqKey);
        
        if (lReqKey == 0) { //not found
          logger.info("A request was not found for a user: " + usr.getLogin());
          break;
        }
        
        logger.finest("getting an approver for the request..: " + lReqKey);
        String strApprover = 
          getRequestApproverFromWF(String.valueOf(lReqKey));
        logger.info("approvers login: " + strApprover);
        if (strApprover.isEmpty()) {
          logger.warning("Can't find an approver for request: " + lReqKey);
          break;
        }
        
        logger.finest("searching for a manager key...");
        try {
          User usrApprover =
            usrMgr.getDetails(UserManagerConstants.AttributeName.USER_LOGIN.getId(),
                              strApprover, null);
          logger.info("manager key: " + usrApprover.getEntityId());

          User usrToBe = new User(usr.getEntityId());
          usrToBe.setManagerKey(usrApprover.getEntityId());

          logger.finest("setting a manager for the user...");
          usrMgr.modify(usrToBe);
          logger.info("Manager: " + usrApprover.getLogin() +
                      " has been successfully assigned for User: " + 
                      usr.getLogin());
          
          // remove a login from the list, as a manager has been assigned
          arLogins.remove(arLogins.size()-1);
        } catch (Exception e) {
          logger.log(Level.SEVERE, "Exception", e);
          break;
        }
      }
    }
    
    logger.finest("creating notification event...");
    // prepare to notify with a list of users
    NotificationEvent event = new NotificationEvent();
    
    logger.finest("List of failed logins: " + arLogins.toString());
    HashMap<String,Object> params = new HashMap<>();
    params.put("login_list", arLogins.toString());
    params.put("userLoginId", strUserToNotify);
    event.setParams(params);
    
    String[] arTo = { strUserToNotify };
    event.setUserIds(arTo);
    
    event.setTemplateName(strTemplateName);
    
    logger.finest("sending notification...");
    if (!notifSrv.notify(event)) {
      logger.warning("Notification failed");
    }
    
    logger.exiting(TAG, "execute");
  }


  /**
   * Searches for a request number of a user was approved on
   * @param strUserLogin User login to search for
   * @return RequestKey or 0
   */
  private Long getRequestNumberForUser(String strUserLogin) {
    Long lRet = 0L;
    String strSQL = 
      "SELECT request.REQUEST_KEY as REQ_KEY " +
      "FROM request " +
        "INNER JOIN request_entities " +
        "ON request_entities.REQUEST_KEY = request.REQUEST_KEY " +
        "INNER JOIN REQUEST_ENTITY_DATA " +
        "ON REQUEST_ENTITY_DATA.REQUEST_ENTITY_KEY = request_entities.REQUEST_ENTITY_KEY " +
    "WHERE request_entities.REQUEST_ENTITITY_OPERATION = 'SELFREGISTER' " +
      "AND REQUEST_ENTITY_DATA.ENTITY_FIELD_NAME = 'User Login' " +
      "AND request.REQUEST_STATUS = 'Request Completed' " +
      "AND REQUEST_ENTITY_DATA.ENTITY_FIELD_VALUE = :1";
    
    logger.entering(TAG, "getRequestNumberForUser", strUserLogin);

    try (PreparedStatement stmt = connDB.prepareStatement(strSQL)) {
      logger.finest("preparing statement...");
      stmt.setString(1, strUserLogin);
      
      logger.finest("running query...");
      try (ResultSet rs = stmt.executeQuery()) {
      
        logger.finest("fetching result...");
        while (rs.next()) {
          lRet = rs.getLong("REQ_KEY");
        }
      }
    } catch (SQLException e) {
      logger.log(Level.SEVERE, "Exception", e);
    }
    logger.exiting(TAG, "getRequestNumberForUser", 
                   "login: " + strUserLogin + " -> Req: " + lRet);
    return lRet;
  }

  /**
   * Retrieves from WorkflowInterface a request approver for a given request id
   * @param strReqId Request to search 
   * @return strLogin on success, empty string otherwise
   */
  private String getRequestApproverFromWF(String strReqId) {
    logger.entering(TAG, "getRequestApproverFromWF", strReqId);

    String strRet = "";

    try {

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
  
  public HashMap getAttributes() {
    return new HashMap();
  }

  public void setAttributes() {
  }
}
