package com.icsynergy.processplugins;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import oracle.iam.identity.exception.NoSuchRoleException;
import oracle.iam.identity.exception.RoleLookupException;
import oracle.iam.identity.exception.RoleModifyException;
import oracle.iam.identity.exception.SearchKeyNotUniqueException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.vo.Identity;
import oracle.iam.platform.Platform;
import oracle.iam.platform.kernel.EventFailedException;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;


public class OrgIDChangedHandler implements PostProcessHandler {
  public EventResult execute(long l, long l2, Orchestration orchestration) {
    final String UDFGRPID = "grp_id";
    final Logger logger = Logger.getLogger("com.icsynergy");
    
    Map params = orchestration.getParameters();
    logger.entering(this.getClass().getCanonicalName(), "execute", 
                    params);

    if (!params.containsKey(UDFGRPID)) {
      logger.finest("Orchestration doesn't have Group ID attribute. Exiting...");
      return new EventResult();  
    }
    
    Identity oldOrg = 
      (Identity) orchestration.getInterEventData().get("CURRENT_ORG");
    String strOldId = 
      String.valueOf(oldOrg.getAttribute(UDFGRPID));
    String strNewId =
      String.valueOf(orchestration.getParameters().get(UDFGRPID));
    logger.finest("oldId: " + strOldId + " newId: " + strNewId);
    
    changeMainRole(strOldId, strNewId);
    changeAdminRole(strOldId, strNewId);
    
    logger.exiting(this.getClass().getCanonicalName(), "execute");
    return new EventResult();
  }


  public BulkEventResult execute(long l, long l2,
                                 BulkOrchestration bulkOrchestration) {
    return null;
  }

  /**
   * Changes main role description from strOldId to strNewId
   * @param strOldId
   * @param strNewId
   */
  private void changeMainRole(String strOldId, String strNewId) {
    final Logger logger = Logger.getLogger("com.icsynergy");
    logger.entering(this.getClass().getCanonicalName(), "changeMainRole",
                "from: " + strOldId + " to: " + strNewId);
    
    RoleManager roleMgr = Platform.getService(RoleManager.class);
    
    HashMap<String,Object> mapRoleAttr = new HashMap<>();
    // prepare a map of changed attributes
    mapRoleAttr.put(RoleManagerConstants
                   .RoleAttributeName.DESCRIPTION
                   .getId(),
                    strNewId);
    Role roleToBe = new Role(mapRoleAttr);

    try {
      roleMgr.modify(RoleManagerConstants
                   .RoleAttributeName.DESCRIPTION
                   .getId(),
                   strOldId,
                   roleToBe);
      logger.fine("Main role has been successfully modified");
    } catch (RoleLookupException 
             | SearchKeyNotUniqueException 
             | NoSuchRoleException e) {
      throw new EventFailedException("AWS-00001", 
                                     "Exception searching main role with desc: " 
                                     + strOldId, 
                                     "",
                                     this.getClass().getName(), 
                                     e);               
    } catch ( RoleModifyException
             | ValidationFailedException e) {
      throw new EventFailedException("AWS-00001", 
                                     "Exception changing main role with desc: " 
                                     + strOldId, 
                                     "",
                                     this.getClass().getName(), 
                                     e);
    }
    Logger
      .getLogger("com.icsynergy")
      .exiting(this.getClass().getCanonicalName(), "changeMainRole");
  }
  
  /**
   * Changes AWS Admin role name from old org id to a new one
   * @param strOldId Old Organization ID
   * @param strNewId Old Organization ID
   */
  private void changeAdminRole(String strOldId, String strNewId) {
    final Logger logger = Logger.getLogger("com.icsynergy");
    logger.entering(this.getClass().getCanonicalName(), "changeAdminRole");
    
    RoleManager roleMgr = Platform.getService(RoleManager.class);
    
    HashMap<String,Object> mapRoleAttr = new HashMap<>();
    mapRoleAttr.put(RoleManagerConstants.RoleAttributeName.NAME.getId(),
                    "aws_delegated_admin_" + strNewId);
    Role roleToBe = new Role(mapRoleAttr);

    try {
      roleMgr.modify(RoleManagerConstants.RoleAttributeName.NAME.getId(),
                   "aws_delegated_admin_" + strOldId,
                   roleToBe);
      logger.fine("Admin role has been successfully modified");
    } catch (NoSuchRoleException 
             | RoleLookupException 
             | SearchKeyNotUniqueException e) {
      throw 
        new EventFailedException("AWS-00001", 
                                 "Exception searching aws admin role with name: " 
                                 + "aws_delegated_admin_" + strOldId,
                                 "", this.getClass().getName(), e);   
    } catch (ValidationFailedException | RoleModifyException e) {
      throw 
        new EventFailedException("AWS-00001", 
                                 "Exception changing aws admin role with name: " 
                                 + "aws_delegated_admin_" + strOldId,
                                 "", this.getClass().getName(), e);       
    }
    logger.exiting(this.getClass().getCanonicalName(), "changeAdminRole");
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
