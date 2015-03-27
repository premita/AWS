package com.icsynergy.processplugins;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import oracle.iam.identity.exception.NoSuchUserException;
import oracle.iam.identity.exception.RoleMemberException;
import oracle.iam.identity.exception.UserLookupException;
import oracle.iam.identity.exception.UserModifyException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.Platform;
import oracle.iam.platform.kernel.EventFailedException;
import oracle.iam.platform.kernel.spi.ConditionalEventHandler;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;


public class RoleDescriptionChangedHandler implements ConditionalEventHandler,
                                                      PostProcessHandler {
  public boolean isApplicable(AbstractGenericOrchestration abstractGenericOrchestration) {
    Logger
      .getLogger("com.icsynergy")
      .entering(this.getClass().getCanonicalName(), "isApplicable");
    return 
      abstractGenericOrchestration
      .getParameters()
      .containsKey(RoleManagerConstants.RoleAttributeName.DESCRIPTION.getId());
  }

  public EventResult execute(long l, long l2,
                             oracle.iam.platform.kernel.vo.Orchestration orchestration) {
    final Logger logger = Logger.getLogger("com.icsynergy");
    logger.entering(this.getClass().getCanonicalName(), "execute",
                    orchestration.getParameters());
    
    Role oldRole = 
      (Role) orchestration.getInterEventData()
      .get(orchestration.getTarget().getEntityId());
    String strOldDesc = oldRole.getDescription();
    
    String strNewDesc = 
      String.valueOf(orchestration
                     .getParameters()
                     .get(RoleManagerConstants
                          .RoleAttributeName.DESCRIPTION.getId()));
    logger.finest("Old desc: " + strOldDesc + " new desc: " + strNewDesc);
    
    RoleManager roleMgr = Platform.getService(RoleManager.class);
    try {
      for (User usr : roleMgr.getRoleMembers(orchestration
                                             .getTarget()
                                             .getEntityId(), true)) {
        changeUserGrpIdUDF(usr.getEntityId(), strOldDesc, strNewDesc);
      }
    } catch (RoleMemberException e) {
      throw 
        new EventFailedException("AWS-00001", 
                                 "Exception searching for members of the role_key: " 
                                 + oldRole.getEntityId(), 
                                 "",
                                 this.getClass().getName(), 
                                 e);
    }
    
    logger.exiting(this.getClass().getCanonicalName(), "execute");
    return new EventResult();
  }

  /**
   * Changes user Group IDs UDF based on old and new value of a changed GrpId
   * @param strUserKey User key
   * @param strOldGrpId Old Grp Id value
   * @param strNewGrpId New Grp Id value
   */
  private void changeUserGrpIdUDF(String strUserKey, String strOldGrpId, 
                                  String strNewGrpId) {
    final Logger logger = Logger.getLogger("com.icsynergy");
    final String UDFGRPSID = "AWSMgmtGrpIDs";
    
    logger.entering(this.getClass().getCanonicalName(), "changeUserGrpIdUDF",
                    String
                    .format("usr_key:%s old_grp_id:%s new_grp_id:%s", 
                            strUserKey, strOldGrpId, strNewGrpId));
    
    UserManager usrMgr = Platform.getService(UserManager.class);
    User usr;

    logger.finest("Getting user details...");
    try {
      usr =
          usrMgr.getDetails(strUserKey,
                        new HashSet<String>(Arrays.asList(UDFGRPSID)), false);
    } catch (NoSuchUserException | UserLookupException e) {
      throw 
        new EventFailedException("AWS-00001", 
                                 "Exception searching for user details usr_key: " 
                                 + strUserKey, 
                                 "",
                                 this.getClass().getName(), 
                                 e);
    }

    String strGrpIds = String.valueOf(usr.getAttribute(UDFGRPSID));
    
    // split a comma separated list
    Set<String> set = new HashSet<>();
    if (!strGrpIds.isEmpty())
      set = new HashSet<>(Arrays.asList(strGrpIds.split(",")));
    
    // replace old grp id with a new
    set.remove(strOldGrpId);
    set.add(strNewGrpId);

    // join the set back to the string
    strGrpIds = set.toString().replaceAll("[\\[\\]]", "").replaceAll(", ", ",");
    logger.finest("UDF to set: " + strGrpIds);
    
    HashMap<String,Object> mapToBe = new HashMap<>();
    mapToBe.put(UDFGRPSID, strGrpIds);
    User usrToBe = new User(strUserKey, mapToBe);

    try {
      if (usrMgr.modify(usrToBe).getStatus().equals("COMPLETED"))
        logger.fine("User's UDF has been successfully set");
    } catch (UserModifyException |
             NoSuchUserException |
             ValidationFailedException e) {
      throw 
        new EventFailedException("AWS-00001", 
                                 "Exception modifying user usr_key: " 
                                 + strUserKey, 
                                 "",
                                 this.getClass().getName(), 
                                 e);
    }
    logger.exiting(this.getClass().getCanonicalName(), "changeUserGrpIdUDF");
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
