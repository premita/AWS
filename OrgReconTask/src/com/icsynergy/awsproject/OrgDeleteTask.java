package com.icsynergy.awsproject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import oracle.iam.identity.exception.NoSuchRoleException;
import oracle.iam.identity.exception.OrganizationManagerException;
import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.vo.OrgUserRelationship;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.Platform;
import oracle.iam.scheduler.vo.TaskSupport;


public class OrgDeleteTask extends TaskSupport {
	public OrgDeleteTask() {
		super();
	}

	public void execute(HashMap hashMap) throws Exception {
		Logger logger = Logger.getLogger("com.icsynergy");
		logger.entering(this.getName(), "execute", hashMap.toString());
		
		String strOrgName = 
			hashMap.get("Organization Name") == null ? null : hashMap.get("Organization Name").toString();
		if (strOrgName.isEmpty()) {
			logger.warning("Organization name is empty. Exiting...");
			return;
		}
		
		OrganizationManager orgMgr = Platform.getService(OrganizationManager.class);
		Organization org;
		try {
			org = orgMgr.getDetails(strOrgName, null, true);
		} catch (OrganizationManagerException e) {
			throw new Exception("Can't find an organization with name: " + strOrgName);
		}
		
	  // get OrgUserRelationship list for this organization
	  List<OrgUserRelationship> lstUserMembers;
	  
	  try {
	    lstUserMembers =
	           orgMgr.getOrganizationMembersRelations(org.getEntityId(),
	                                          null, null, null);
	    logger.finest(lstUserMembers.toString());
	  } catch (OrganizationManagerException e) {
	    throw new Exception(e.getErrorMessage());
	  }
		
		// for each user with HOME relationship -> delete the user
		UserManager usrMgr = Platform.getService(UserManager.class);
		for (OrgUserRelationship rel : lstUserMembers) {
			if (rel.getRelationType() == OrgUserRelationship.RelationType.HOME) {
			  logger.finest(String
			                .format("User %s is %s", 
			                        rel.getUser(), rel.getRelationType().toString()));

				usrMgr.delete(rel.getUser().getEntityId(), false);
				logger.finer("User has been deleted: " + rel.getUser().getLogin());
			}
		}
		
		// delete roles
		RoleManager roleMgr = Platform.getService(RoleManager.class);

		Role role = null;
	  List<User> lstUsr = null;
	  Set<String> setUserKeys = null;

		// get a list of users for a role "Display Name" == "Org Name"
		try {
			role =
					 roleMgr.getDetails(RoleManagerConstants.RoleAttributeName.DISPLAY_NAME.getId(),
															strOrgName, null);
		  lstUsr = 
		    roleMgr
		    .getRoleMembers(role.getEntityId(), true);
		  logger.finest("List of users with role: " + role.getDisplayName() 
		                + " -> " + lstUsr.toString());

		  // for each user revoke the role
		  setUserKeys = new HashSet<>();
		  for (User usr : lstUsr) {
		    setUserKeys.add(usr.getEntityId());
		  }
		  
		  if (setUserKeys.size() > 0) {
		    logger.finest("Set of user keys to revoke role from -> " 
		                  + setUserKeys.toString());
		  
		    if ("COMPLETED".equalsIgnoreCase(roleMgr
		                                     .revokeRoleGrant(role.getEntityId(), 
		                                                      setUserKeys).getStatus())) {
		      logger.finest("Role has been revoked from users: " + role.getDisplayName());
		    }
		  }
		  // delete the role
		  if ("COMPLETED".equalsIgnoreCase(roleMgr
		                                   .delete(RoleManagerConstants
		                                           .RoleAttributeName.DISPLAY_NAME
		                                           .getId(), strOrgName)
		                                   .getStatus())) {
		    logger.finest("Role successfully deleted: " + strOrgName);
		  } else {
		    throw new Exception("Can't delete role: " + strOrgName);
		  }
		} catch (NoSuchRoleException nsre) {
				logger.warning("Role: " + strOrgName + " not found");
		}
		
		
	  // get a list of users for a role "Display Name" == "Org Name Admin"
		try {
			role =
				roleMgr.getDetails(RoleManagerConstants.RoleAttributeName.DISPLAY_NAME.getId(),
									 strOrgName + " Admin", null);
			lstUsr = roleMgr.getRoleMembers(role.getEntityId(), true);
			logger.finest("List of users with role: " + role.getDisplayName() + " -> " +
										lstUsr.toString());

			// for each user revoke the role
			setUserKeys = new HashSet<>();
			for (User usr : lstUsr) {
				setUserKeys.add(usr.getEntityId());
			}

			if (setUserKeys.size() > 0) {
				logger.finest("Set of user keys to revoke role from -> " +
											setUserKeys.toString());

				if ("COMPLETED".equalsIgnoreCase(roleMgr.revokeRoleGrant(role.getEntityId(),
																																 setUserKeys).getStatus())) {
					logger.finest("Role has been revoked from users: " +
												role.getDisplayName());
				}
			}

			// delete the role
			if ("COMPLETED".equalsIgnoreCase(roleMgr.delete(RoleManagerConstants.RoleAttributeName.DISPLAY_NAME.getId(),
																											strOrgName +
																											" Admin").getStatus())) {
				logger.finest("Role successfully deleted: " + strOrgName + " Admin");
			} else {
				throw new Exception("Can't delete role: " + strOrgName + " Admin");
			}
		} catch (NoSuchRoleException nsre) {
			logger.warning("Role: " + strOrgName + " Admin not found");
		}
		
		// delete the organization
		orgMgr.delete(strOrgName, true);
		logger.fine("Organization deleted: " + strOrgName);
		
		logger.exiting(this.getName(), "execute");
	}

	public HashMap getAttributes() {
		return null;
	}

	public void setAttributes() {
	}
}
