package com.icsynergy.awsproject;

import java.util.HashMap;

import java.util.HashSet;
import java.util.List;

import java.util.Set;

import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.platform.entitymgr.vo.SearchRule;
import oracle.iam.scheduler.vo.TaskSupport;

public class RuleAdjuster extends TaskSupport {

  public void execute(HashMap hashMap) throws Exception {
    System.out.println("execute entered");
    RoleManager roleMgr = Platform.getService(RoleManager.class);
    
    SearchCriteria crit = 
      new SearchCriteria(RoleManagerConstants.RoleAttributeName.NAME.getId(), "*", SearchCriteria.Operator.EQUAL);
    
    Set<String> setAttrs = new HashSet<String>();
    List<Role> lstRoles = roleMgr.search(crit, setAttrs, null);
    for (Role role : lstRoles) {
      SearchRule rule = roleMgr.getUserMembershipRule(role.getEntityId());
      roleMgr.setUserMembershipRule(role.getEntityId(), null);
/*      
      if (rule != null && !rule.getSecondArgument().toString().equalsIgnoreCase(role.getName()))
        System.out.println("RoleName: " + role.getName() + " Rule: " + rule.getSecondArgument().toString());*/
    }
  }

  public HashMap getAttributes() {
    return null;
  }

  public void setAttributes() {
  }
}
