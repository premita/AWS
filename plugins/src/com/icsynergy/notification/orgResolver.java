package com.icsynergy.notification;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.logging.Logger;

import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.notification.impl.NotificationEventResolver;
import oracle.iam.notification.vo.NotificationAttribute;
import oracle.iam.platform.Platform;

public class orgResolver implements NotificationEventResolver {
	private final static String TAG = "orgResolver";
	private final static Logger logger = Logger.getLogger("com.icsynergy");
	
	public orgResolver() {
		super();
	}

	public List<NotificationAttribute> getAvailableData(String string,
																											Map<String, Object> map) throws Exception {
		return Collections.emptyList();
	}

	/**
	 * Resolves organization key into organization name
	 * @param string
	 * @param map
	 * @return hashmap containing organization name (org_name)
	 * @throws Exception
	 */
	public HashMap<String, Object> getReplacedData(String string,
																								 Map<String, Object> map) throws Exception {
		logger.entering(TAG, "getReplacedData", 
										new StringBuilder(string).append(map.toString()));
		
		if (map.get("act_key") != null) {
			String strOrgKey = map.get("act_key").toString();
			
			OrganizationManager orgMgr = 
				Platform.getService(OrganizationManager.class);
			
			if (orgMgr == null)
				throw new Exception("Can't get OrganizationManager service");
			
			Organization org = orgMgr.getDetails(strOrgKey, null, false);
			
			map.put("org_name", org.getAttribute(OrganizationManagerConstants
																					 .AttributeName.ORG_NAME
																					 .getId()).toString());
		} else if (!map.containsKey("org_name")){
			throw new Exception("neither org_name nor act_key, can't resolve, exiting...");
		}
		
		logger.exiting(TAG, "getReplacedData", map);
		return new HashMap<String,Object>(map);
	}
}
