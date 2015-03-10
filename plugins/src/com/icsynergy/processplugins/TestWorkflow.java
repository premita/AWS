package com.icsynergy.processplugins;

import com.icsynergy.helpers.csf.CsfAccessor;
import com.icsynergy.helpers.csf.CsfReadAction;

import java.util.ArrayList;
import java.util.HashMap;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import java.util.logging.Logger;

import javax.management.MBeanServer;

import javax.management.ObjectName;

import javax.naming.InitialContext;

import oracle.bpel.services.workflow.client.IWorkflowServiceClient;
import oracle.bpel.services.workflow.client.IWorkflowServiceClientConstants;
import oracle.bpel.services.workflow.client.WorkflowServiceClientFactory;
import oracle.bpel.services.workflow.client.config.RemoteClientType;
import oracle.bpel.services.workflow.client.config.ServerType;
import oracle.bpel.services.workflow.client.config.WorkflowServicesClientConfigurationType;
import oracle.bpel.services.workflow.query.ITaskQueryService;
import oracle.bpel.services.workflow.repos.Ordering;
import oracle.bpel.services.workflow.repos.Predicate;
import oracle.bpel.services.workflow.repos.TableConstants;
import oracle.bpel.services.workflow.task.model.Task;
import oracle.bpel.services.workflow.verification.IWorkflowContext;

import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;

import oracle.security.jps.service.credstore.PasswordCredential;

import oracle.tip.pc.services.identity.BPMIdentityService;

public class TestWorkflow implements PostProcessHandler {
  public TestWorkflow() {
    super();
  }

  public EventResult execute(long l, long l2, Orchestration orchestration) {
    Logger logger = Logger.getLogger("com.icsynergy");
    logger.entering(this.getClass().getCanonicalName(), "execute");
    
    try {
      logger.finest("getting initial context...");
      InitialContext ctx = new InitialContext();
      
      logger.finest("looking up MBeanServer interface...");
      MBeanServer server = (MBeanServer) ctx.lookup("java:comp/env/jmx/runtime");
      
      ObjectName objName = 
        new ObjectName("oracle.iam:name=SOAConfig,type=XMLConfig.SOAConfig,XMLConfig=Config," +
                        "Application=oim,ApplicationVersion=11.1.2.0.0");
      
      logger.finest("getting MBean attributes...");
      String rmiURL = (String) server.getAttribute(objName, "Rmiurl");
      String passKey = (String) server.getAttribute(objName, "PasswordKey");
      logger.finest("rmiurl=" + rmiURL + " passKey=" + passKey);
      
      logger.finest("getting credentials from the key store...");
      CsfAccessor credReader = new CsfAccessor();
      PasswordCredential creds = credReader.readCredentialsfromCsf("oim", passKey);
        
      String strSOAUserName = creds.getName();
      String strSOAPassword = new String(creds.getPassword());
      
      Map properties = new HashMap(); 
      properties.put(IWorkflowServiceClientConstants.CONNECTION_PROPERTY.CLIENT_TYPE, WorkflowServiceClientFactory.REMOTE_CLIENT); 
      properties.put(IWorkflowServiceClientConstants.CONNECTION_PROPERTY.EJB_PROVIDER_URL, rmiURL); 
      
      logger.finest("getting workflow service client...");
      IWorkflowServiceClient client = 
        WorkflowServiceClientFactory.getWorkflowServiceClient(properties, null); 
      
      ITaskQueryService querySvc = client.getTaskQueryService();
      logger.finest("authenticating ...");
      IWorkflowContext wfCtx = 
        querySvc.authenticate(strSOAUserName, strSOAPassword.toCharArray(), 
                              "jazn.com");
         
       // Build the predicate
       Predicate idPredicate = new Predicate(TableConstants.WFTASK_IDENTIFICATIONKEY_COLUMN,
                                    Predicate.OP_EQ,
                                    "423");
      idPredicate.addClause(Predicate.AND, TableConstants.WFTASK_OUTCOME_COLUMN, 
                            Predicate.OP_EQ, "APPROVE");
      idPredicate.addClause(Predicate.AND, TableConstants.WFTASK_HASSUBTASK_COLUMN, 
                            Predicate.OP_NEQ, "T");
       
       // Create the ordering
       Ordering ordering = new Ordering(TableConstants.WFTASK_TITLE_COLUMN, true, true);        
         ordering.addClause(TableConstants.WFTASK_PRIORITY_COLUMN, true, true);
         
       // List of display columns
       // For those columns that are not specified here, the queried Task object will not hold any value.
       // For example: If TITLE is not specified, task.getTitle() will return null
       // For the list of most comonly used columns, check the table below
       // Note: TASKID is fetched by default. So there is no need to explicitly specity it.
       List queryColumns = new ArrayList();
        queryColumns.add("TASKNUMBER");     
        queryColumns.add("APPROVERS");     
         
      logger.finest("querying...");
       List tasksList = querySvc.queryTasks(wfCtx,
                                   queryColumns,
                                   null,
                                   ITaskQueryService.AssignmentFilter.ALL,
                                   null,
                                   idPredicate, 
                                   ordering, 
                                   0,0); // No Paging
                                   
       // How to use paging:
       // 1. If you need to dynamically calculate paging size (or) to display/find 
       //    out the number of pages, the user has to scroll (Like page X of Y)
       //      Call countTasks to find out the number of tasks it returns. Using this 
       //      calculate your paging size (The number of taks you want in a page)
       //      Call queryTasks successively varing the startRow and endRow params.  
       //      For example: If the total number of tasks is 30 and your want a paging size
       //      of 10, you can call with (startRow, endRow): (1, 10) (11, 20) (21, 30) 
       // 2. If you have fixed paging size, just keep calling queryTasks successively with 
       //      the paging size (If your paging size is 10, you can call with (startRow, endRow): 
       //      (1, 10) (11, 20) (21, 30) (31, 40).....  until the number of tasks returned is 
       //      less than your paging size (or) there are no more tasks returned.
       // 3. If no paging is specified (startRow and endRow are both zero), then the
       //       first 200 rows will be returned. If for some reason, you require more
       //       than 200 rows, you should explicilty specify a larger paging size.
  
       if (tasksList != null) { // There are tasks 
         System.out.println("Total number of tasks: " + tasksList.size()); 
         System.out.println("Tasks List: ");
         Task task = null; 
         for (int i = 0; i < tasksList.size(); i++) { 
           task = (Task) tasksList.get(i);          
           logger.finest("Task Number: " + task.getSystemAttributes().getTaskNumber());
           logger.finest("Approvers: " + task.getSystemAttributes().getApprovers());
           // Retrive any Optional Info specified
           // Use task service, to perform operations on the task
         }
       }
    } catch (Exception e) {
    logger.log(Level.SEVERE, "Exception", e);
    }

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
}
