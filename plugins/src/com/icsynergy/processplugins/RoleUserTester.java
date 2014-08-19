package com.icsynergy.processplugins;

import Thor.API.Exceptions.tcAPIException;
import Thor.API.Exceptions.tcStaleDataUpdateException;
import Thor.API.Exceptions.tcUserNotFoundException;
import Thor.API.Operations.tcOrganizationOperationsIntf;

import java.util.HashMap;

import java.util.logging.Logger;

import oracle.iam.platform.Platform;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;

import Thor.API.Operations.tcUserOperationsIntf;
import Thor.API.Operations.tcITResourceInstanceOperationsIntf;
import Thor.API.tcResultSet;

import java.util.Hashtable;
import java.util.logging.Level;
import com.icsynergy.helpers.tcResultSetHelper;


public class RoleUserTester implements PostProcessHandler {
  private final static Logger logger = Logger.getLogger("com.icsynergy");
  private final static String TAG = RoleUserUDFSetter.class.getCanonicalName();
  
  public EventResult execute(long l, long l2, Orchestration orchestration) {
    logger.entering(TAG, "execute");
    
    tcITResourceInstanceOperationsIntf itrAPI = 
      Platform.getService(tcITResourceInstanceOperationsIntf.class);

    Hashtable map = new Hashtable();
    map.put("IT Resources.Name", "WSP_VENUE_GTC");
    tcResultSet res;
    try {
      res = itrAPI.findITResourceInstancesAnonymous(map);
      
      tcResultSetHelper.printResultSet(res, "IT Data", logger);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Exception", e);
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
}
