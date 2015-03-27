package com.icsynergy.processplugins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import oracle.iam.identity.exception.RoleGrantRevokeException;
import oracle.iam.identity.exception.UserMembershipException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.rolemgmt.vo.RoleManagerResult;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.platform.kernel.EventFailedException;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;


public class RevokeAWSAdminOnUserDisable implements PostProcessHandler {
  public EventResult execute(long l, long l2, Orchestration orchestration) {
    Logger
      .getLogger("com.icsynergy")
      .entering(this.getClass().getCanonicalName(), 
                "execute");
    
    RevokeAdminRole(orchestration.getTarget().getEntityId());
    
    Logger
      .getLogger("com.icsynergy")
      .exiting(this.getClass().getCanonicalName(), 
                "execute");    
    return new EventResult();
  }

  public BulkEventResult execute(long l, long l2,
                                 BulkOrchestration bulkOrchestration) {
    Logger
      .getLogger("com.icsynergy")
      .entering(this.getClass().getCanonicalName(), 
                "bulk execute");

    for (String strUserKey : bulkOrchestration.getTargetUserIds()){
      RevokeAdminRole(strUserKey);
    }

    Logger
      .getLogger("com.icsynergy")
      .exiting(this.getClass().getCanonicalName(), 
                "bulk execute");    
    return new BulkEventResult();
  }

  /**
   * Revoke AWS admin roles from a user
   * @param strUserKey A user key to revoke roles from
   * @throws EventFailedException
   */
  private void RevokeAdminRole(String strUserKey) throws EventFailedException {
    final Logger logger = Logger.getLogger("com.icsynergy");
    logger.entering(this.getClass().getCanonicalName(), "RevokeAdminRole", strUserKey);
    
    RoleManager roleMgr = Platform.getService(RoleManager.class);
    // search only for aws_delegated_admin roles
    SearchCriteria critAWSAdminRoles = 
      new SearchCriteria(RoleManagerConstants.RoleAttributeName.NAME.getId(),
                         "aws_delegated_admin",
                         SearchCriteria.Operator.BEGINS_WITH);
    Set<String> set = 
      new HashSet<>(Arrays.asList(RoleManagerConstants
                                  .RoleAttributeName.KEY.getId()));
    
    List<String> lstRoleKeys = new ArrayList<>();
    try {
      for (Role role : roleMgr.getUserMemberships(strUserKey, 
                                                  critAWSAdminRoles, set, 
                                                  null, false)){
        lstRoleKeys.add(role.getEntityId());
      }
      
      logger.finest("preparing to revoke roles: " + lstRoleKeys);
      if (lstRoleKeys.size() > 0) {
        RoleManagerResult res = 
          roleMgr.revokeRoleGrants(strUserKey, new HashSet<String>(lstRoleKeys));
        logger.finest(String.
                      format("Result is: [Status=%s, Success=%s, Failed=%s)",
                             res.getStatus(), res.getSucceededResults(),
                             res.getFailedResults()));
      }
    } catch (UserMembershipException e) {
      throw new EventFailedException("AWS-00001", 
                                     "Exception getting roles for user: " 
                                     + strUserKey, 
                                     "",
                                     this.getClass().getName(), 
                                     e);
    } catch (ValidationFailedException | RoleGrantRevokeException e) {
      throw new EventFailedException("AWS-00002", 
                                     "Exception revoking roles from user: " 
                                     + strUserKey, 
                                     "",
                                     this.getClass().getName(), 
                                     e);    
    }

    logger.exiting(this.getClass().getCanonicalName(), "RevokeAdminRole", strUserKey);
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
