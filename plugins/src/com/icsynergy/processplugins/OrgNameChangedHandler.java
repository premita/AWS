package com.icsynergy.processplugins;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.iam.identity.exception.NoSuchRoleException;
import oracle.iam.identity.exception.RoleLookupException;
import oracle.iam.identity.exception.RoleModifyException;
import oracle.iam.identity.exception.SearchKeyNotUniqueException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.vo.Identity;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchRule;
import oracle.iam.platform.kernel.EventFailedException;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;


public class OrgNameChangedHandler implements PostProcessHandler {
  public EventResult execute(long l, long l2, Orchestration orchestration) {
    final Logger logger = Logger.getLogger("com.icsynergy");
    final String TAG = OrgNameChangedHandler.class.getCanonicalName();
    final String UDFGRPID = "grp_id";

    Map params = orchestration.getParameters();
    logger.entering(TAG, "execute", params);

    if (!params.containsKey(OrganizationManagerConstants
                            .AttributeName.ORG_NAME.getId())) {
      logger.finest("Orchestration doesn't have Name attribute. Exiting...");
      return new EventResult();  
    }
    
    // new organization name
    String strOrgName = 
      String.valueOf(params.get(OrganizationManagerConstants
                                .AttributeName.ORG_NAME.getId()));
        
    // get old organization name
    Identity oldOrg = 
      (Identity) orchestration.getInterEventData().get("CURRENT_ORG");
    String strOldOrgName = 
      String.valueOf(oldOrg
                     .getAttribute(OrganizationManagerConstants
                                   .AttributeName.ORG_NAME.getId()));
    logger.finest("Old organization name: " + strOldOrgName
                  + " New org name: " + strOrgName);
    
    RoleManager roleMgr = Platform.getService(RoleManager.class);

    Set<String> set = 
      new HashSet<>(Arrays
                    .asList(RoleManagerConstants
                            .RoleAttributeName.KEY.getId()));

    // searching for the main role and changing it
    try {
      Role role = 
        roleMgr.getDetails(RoleManagerConstants.RoleAttributeName.NAME.getId() 
                           , strOldOrgName 
                           , set);
      
      logger.finest("Changing main role name and display name...");
      Role roleToBe = new Role(role.getEntityId());
      roleToBe.setName(strOrgName);
      roleToBe.setDisplayName(strOrgName);
      
      roleMgr.modify(roleToBe);
      logger.fine("Role: " + strOldOrgName + " has been changed to: " +
                    strOrgName);
      
      // change Role membership rule
      logger.finest("Setting membership rule...");
      SearchRule rule = 
        new SearchRule(UserManagerConstants 
                       .AttributeName.USER_ORGANIZATION 
                       .getId(),
                       strOrgName,
                       SearchRule.Operator.EQUAL);
      roleMgr.setUserMembershipRule(role.getEntityId(), rule, false);
      logger.fine("Membership rule has been set");
    } catch (SearchKeyNotUniqueException |
             NoSuchRoleException |
             ValidationFailedException |
             RoleLookupException |
             RoleModifyException e) {
      logger.log(Level.SEVERE
                 , "Exception changing a role with name: " + strOldOrgName
                 , e);
      throw new EventFailedException("AWS-00001", 
                                     "Exception changing a role with name: " 
                                     + strOldOrgName, 
                                     "",
                                     this.getClass().getName(), 
                                     e);
    }
    
    // search for Admin role and changing it
    try {
      String strOrgId = String.valueOf(oldOrg.getAttribute(UDFGRPID));
      
      HashMap<String,Object> mapToBe = new HashMap<>();
      mapToBe.put(RoleManagerConstants.RoleAttributeName.DISPLAY_NAME.getId(),
                  strOrgName + " Admin");
      Role roleToBe = new Role(mapToBe);

      logger.finest("Changing admin role display name...");
      roleMgr.modify(RoleManagerConstants 
                      .RoleAttributeName.NAME.getId(), 
                     "aws_delegated_admin_" + strOrgId,
                     roleToBe);
      logger.fine("Role: " + strOldOrgName + " Admin has been changed to: " +
                    strOrgName + " Admin");
    } catch (NoSuchRoleException | 
             RoleLookupException | 
             SearchKeyNotUniqueException |
             ValidationFailedException |
             RoleModifyException e) {
      logger.log(Level.SEVERE, "Exception changing an admin role", e);
      throw new EventFailedException("AWS-00001", 
                                     "Exception changing an admin role with name: " 
                                     + strOldOrgName + " Admin", 
                                     "",
                                     this.getClass().getName(), 
                                     e);
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
