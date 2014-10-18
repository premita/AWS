package com.icsynergy.awsproject;

import com.icsynergy.helpers.ITResHelper;
import com.icsynergy.helpers.JDBCHelper;
import com.icsynergy.helpers.SysConfigHelper;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.iam.identity.exception.NoSuchRoleException;
import oracle.iam.identity.exception.NoSuchUserException;
import oracle.iam.identity.exception.OrganizationManagerException;
import oracle.iam.identity.exception.RoleAlreadyExistsException;
import oracle.iam.identity.exception.RoleCreateException;
import oracle.iam.identity.exception.RoleModifyException;
import oracle.iam.identity.exception.RoleSearchException;
import oracle.iam.identity.exception.UserModifyException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.identity.rolemgmt.api.RoleManager;
import oracle.iam.identity.rolemgmt.api.RoleManagerConstants;
import oracle.iam.identity.rolemgmt.vo.Role;
import oracle.iam.identity.rolemgmt.vo.RoleManagerResult;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.platform.authz.exception.AccessDeniedException;
import oracle.iam.platform.Platform;
import oracle.iam.platform.authopss.api.PolicyConstants;
import oracle.iam.platform.authopss.vo.EntityPublication;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.platform.entitymgr.vo.SearchRule;
import oracle.iam.platformservice.api.EntityPublicationService;
import oracle.iam.scheduler.vo.TaskSupport;

public class UserUDFSetterTask extends TaskSupport {
  private static final String TAG = UserUDFSetterTask.class.getCanonicalName();
  private final static Logger m_logger = Logger.getLogger("com.icsynergy");
  
  // custom role modifiers
  private static final String ROLENAMEPREFIX = "aws_delegated_admin_";
  private static final String ROLENAMESUFFIX = "Admin";
  
  // User UDFs
  private static final String UDFGRPNAME = "AWSMgtGrpName";
  private static final String UDFGRPID = "AWSMgmtGrpIDs";
  
  public void execute(HashMap hashMap) throws Exception {
    m_logger.entering(TAG, "execute", hashMap.toString());

		// get task params
		String strLoginPattern = hashMap.get("User login pattern").toString();
		
		// Services
		final RoleManager roleMgr = Platform.getService(RoleManager.class);
	  final UserManager usrMgr = Platform.getService(UserManager.class);
		  
		// for all users
		SearchCriteria crit = 
			new SearchCriteria(UserManagerConstants.AttributeName.USER_LOGIN.getId(),
												 strLoginPattern, SearchCriteria.Operator.EQUAL);
		List<User> lstUsr = usrMgr.search(crit, null, null);
		
		for (User usr : lstUsr) {
			m_logger.info("Processing user: " + usr.getLogin());
			
			// we need to reset UDFs related to GrpID and GrpName
			List<String> lstGrpIDs = new ArrayList<String>();
		  List<String> lstGrpNames = new ArrayList<String>();
			
			List<Role> lstRole = roleMgr.getUserMemberships(usr.getEntityId(), true);
			for (Role role : lstRole) {
				// skip these ones
				if (role.getName().contains(ROLENAMEPREFIX) ||
						role.getName().contains("ALL USERS")) {
							continue;
				} else {
					lstGrpIDs.add(role.getDescription());
					lstGrpNames.add(role.getName());
				}
			}
		  			
			String strGrpIDs = 
				Arrays.toString(lstGrpIDs.toArray(new String[lstGrpIDs.size()])).replaceAll("[\\[\\]]", "");
		  String strGrpNames = 
		    Arrays.toString(lstGrpNames.toArray(new String[lstGrpNames.size()])).replaceAll("[\\[\\]]", "");

			try {
				User usrToBe = new User(usr.getEntityId());
				usrToBe.setAttribute(UDFGRPID, strGrpIDs);
				usrToBe.setAttribute(UDFGRPNAME, strGrpNames);
				usrMgr.modify(usrToBe);
				m_logger.info(String.format("User attributes are set to %s and %s", 
											strGrpIDs, strGrpNames));
			} catch (Exception e) {
				m_logger.log(Level.SEVERE, "Exception modifying a user", e);
			}
		}
		
    m_logger.exiting(TAG, "execute");
  }

  public HashMap getAttributes() {
    return new HashMap();
  }

  public void setAttributes() {
  }
}
