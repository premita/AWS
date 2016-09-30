package com.icsynergy;

import java.io.IOException;
import java.util.HashMap;

import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.iam.identity.exception.OrganizationManagerException;
import oracle.iam.identity.exception.UserAlreadyExistsException;
import oracle.iam.identity.exception.UserCreateException;
import oracle.iam.identity.exception.UserSearchException;
import oracle.iam.identity.exception.ValidationFailedException;
import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.api.OrganizationManagerConstants;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.api.UserManagerConstants;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.identity.usermgmt.vo.UserManagerResult;
import oracle.iam.platform.Platform;
import oracle.iam.platform.entitymgr.vo.SearchCriteria;
import oracle.iam.scheduler.vo.TaskSupport;
import oracle.iam.identity.orgmgmt.vo.Organization;

import oracle.iam.notification.api.NotificationService;
import oracle.iam.notification.exception.EventException;
import oracle.iam.notification.exception.MultipleTemplateException;
import oracle.iam.notification.exception.NotificationException;
import oracle.iam.notification.exception.NotificationResolverNotFoundException;
import oracle.iam.notification.exception.TemplateNotFoundException;
import oracle.iam.notification.exception.UnresolvedNotificationDataException;
import oracle.iam.notification.exception.UserDetailsNotFoundException;
import oracle.iam.notification.vo.NotificationEvent;
import Thor.API.Operations.tcLookupOperationsIntf;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

/**
 * This class is used to peform Pre-certification task to load/enable managers
 * from CORP. It includes 1. Get Supervisor from Phonebook for all active users
 * from given Organizaion in OIM. 2. If Supervisor is found in OIM and
 * Supervisor Id is not matched with current manager in OIM update OIM manager.
 * 3. If Supervisor is found in OIM and it is not Active drop warnings in the
 * logs. 4. If Supervisor is not found in OIM, create it in OIM and assign as
 * Manager to user.
 */
public class AssignManagerInOIMFromPhoneBook extends TaskSupport {
	private static final String TAG = "AssignManagerInOIMFromPhoneBook";
	private static final Logger m_logger = Logger.getLogger("com.icsynergy");

	// get interface
	private final UserManager usrmgr = Platform.getService(UserManager.class);
	private final NotificationService srvNotification = Platform.getService(NotificationService.class);
	private final tcLookupOperationsIntf lookupOperationsIntf = Platform.getService(tcLookupOperationsIntf.class);
	Document doc = null;

	public AssignManagerInOIMFromPhoneBook() {
		super();
	}

	private static final String CONFIGLOOKUP = "Lookup.AWS.Configuration";
	private static final String CONFIGORGCREATEUSERTONOTIFY = "aws.internal.admin";
	private static final String CONFIGWARNINGTEMPLATE = "org.create.notification.warning";
	private static final String PHONEBOOKURL = "phonebook.url";

	/**
	 * This method is used to peform Pre-certification task to load/enable
	 * managers from CORP. It includes 1. Get Supervisor from Phonebook for all
	 * active users from given Organizaion in OIM. 2. If Supervisor is found in
	 * OIM and Supervisor Id is not matched with current manager in OIM update
	 * OIM manager. 3. If Supervisor is found in OIM and it is not Active drop
	 * warnings in the logs. 4. If Supervisor is not found in OIM, create it in
	 * OIM and assign as Manager to user.
	 * 
	 * @param hashMap
	 * @throws Exception
	 */
	public void execute(HashMap hashMap) throws Exception {

		m_logger.entering(TAG, "execute", hashMap.toString());
		String fromOrg = null;
		String createUserInOrg = null;

		// check required parameters
		if (!hashMap.containsKey("Search Users From Organization")
				|| !hashMap.containsKey("Create User In Organization")) {
			m_logger.warning("Requred parameters are missing");
			m_logger.exiting(TAG, "execute");
			return;
		} else {
			fromOrg = hashMap.get("Search Users From Organization").toString();
			m_logger.finest("Search From Organization : " + fromOrg);
			createUserInOrg = hashMap.get("Create User In Organization").toString();
			m_logger.finest("User create in Organization: " + createUserInOrg);
		}

		// searching for users with given organization
		SearchCriteria OrgSearchCriteria = new SearchCriteria("Organization Name", fromOrg,
				SearchCriteria.Operator.EQUAL);

		// searching for active users
		SearchCriteria critActive = new SearchCriteria(UserManagerConstants.AttributeName.STATUS.getName(),
				UserManagerConstants.AttributeValues.USER_STATUS_ACTIVE.getId(), SearchCriteria.Operator.EQUAL);

		// final criteria criteria
		SearchCriteria criteria = new SearchCriteria(OrgSearchCriteria, critActive, SearchCriteria.Operator.AND);

		// Hashset for holding ID
		Set<String> retAttr = new HashSet<String>();
		retAttr.add(UserManagerConstants.AttributeName.USER_LOGIN.getName());
		retAttr.add(UserManagerConstants.AttributeName.MANAGER_KEY.getId());

		// get user list who meet criteria
		List<User> usersFromGivenOrg = usrmgr.search(criteria, retAttr, null);
		m_logger.finest("Users in Organization [" + fromOrg + "] are [ " + usersFromGivenOrg + "]");

		// If no users to process; exit
		if (usersFromGivenOrg.isEmpty()) {
			m_logger.finest("No active users found with given Organization");
			m_logger.exiting(TAG, "execute");
			return;
		}

		// Put userIDs with oim manager into list
		Map usrManagerMap = new HashMap<String, HashMap<String, String>>();
		Map userDetailMap = new HashMap<String, String>();
		for (User usr : usersFromGivenOrg) {
			String oimManagerID = null;
			m_logger.finest("User: " + usr.getId() + " " + usr.getLogin());
			try {
				// Getting manager ID from OIM
				HashMap hm = usr.getAttributes();
				m_logger.finest("hm  =" + hm);
				String oimManagerkey = String
						.valueOf(usr.getAttribute(UserManagerConstants.AttributeName.MANAGER_KEY.getId()));
				m_logger.finest("User Manager Key: " + oimManagerkey);
				if (!isNullOrEmpty(oimManagerkey)) {
					User user = usrmgr.getDetails(oimManagerkey, null, false);
					oimManagerID = user.getLogin().toString();
					m_logger.finest("User Manager ID : " + oimManagerID);
				}
				// getting data from PhoneBook
				userDetailMap = getdataFromPhoneBook(usr, oimManagerID);
				usrManagerMap.put(usr.getId(), userDetailMap);
				processUserManagerData(usrManagerMap, createUserInOrg);
			} catch (Exception e) {
				m_logger.finest("Error while fetching user info for user:[" + usr.getLogin() + "]");
			}
		}
		m_logger.exiting(TAG, "execute");
	}

	/**
	 * This method checks is given string is null or empty.
	 * 
	 * @param strCheck
	 * @return
	 */
	public static boolean isNullOrEmpty(String strCheck) {
		return (strCheck == null) || strCheck.equals("null") || strCheck.trim().length() == 0;
	}

	public HashMap getAttributes() {
		return new HashMap();
	}

	public void setAttributes() {
	}

	/**
	 * This method is used to get data from Phonebook for given user
	 * 
	 * @param usr
	 * @param oimManagerID
	 * @return
	 */
	private Map getdataFromPhoneBook(User usr, String oimManagerID) {
		m_logger.entering(TAG, "getdataFromPhoneBook");
		Map userDetailMap = new HashMap<String, String>();
		try {
			String phoneBookSupId =null;
			String phoneBookURL = (lookupOperationsIntf.getDecodedValueForEncodedValue(CONFIGLOOKUP, PHONEBOOKURL));
			// "http://webphone.att.com/cgi-bin/webphones.pl?id="
			m_logger.finest("Connecting to Phonebook :[" + phoneBookURL + usr.getLogin() + "]");
			doc = Jsoup.connect(phoneBookURL + usr.getLogin()).get();
			String attuid = doc.getElementById("atta1").val();
			m_logger.finest("User Id from Phonebook :[" + attuid + "]");
			Elements h2 = doc.getElementsByAttributeValueMatching("href", "/cgi-bin/webphones.pl*");
			String hrefValue = h2.attr("href");
			m_logger.finest("Href Value :[" + hrefValue + "]");
			String userName = h2.text();
			if(!isNullOrEmpty(hrefValue)){
				 phoneBookSupId = hrefValue.split("id=")[1];
			}
			m_logger.finest("Supervisor ATTUID of user :[" + usr.getId() + "] is [" + phoneBookSupId + "]");
			if(!isNullOrEmpty(userName)){
				userDetailMap.put("firstName", userName.split(" ")[0]);
				userDetailMap.put("lastName", userName.split(" ")[2]);
			}
			userDetailMap.put("phoneBookSupId", phoneBookSupId);		
			userDetailMap.put("oimManager", oimManagerID);
			userDetailMap.put("userLogin", usr.getLogin());
			m_logger.finest("userDetailMap is [" + userDetailMap + "]");
		} catch (HttpStatusException e) {
			m_logger.finest("User not found in PhoneBook :[" + usr.getId() + "] or something is wrong with URL");
			throw new RuntimeException();
		} catch (IOException e) {
			m_logger.finest("Error is gerring while fetching user info for user:[" + usr.getLogin() + "]");
			e.printStackTrace();
		} catch (Exception e) {
			m_logger.finest("Error is gerring while fetching user info for user:[" + usr.getLogin() + "]");
			e.printStackTrace();
		}
		m_logger.exiting(TAG, "getdataFromPhoneBook");
		return userDetailMap;
	}

	/**
	 * This method process the data got from phonebook. Assign Manager to User
	 * if Supervisor is present in OIM or create and assign it.
	 * 
	 * @param usrManagerMap
	 * @param createUserInOrg
	 */
	private void processUserManagerData(Map usrManagerMap, String createUserInOrg) {
		m_logger.entering(TAG, "processUserManagerData");
		m_logger.finest("usrManagerMap [" + usrManagerMap + "]");
		Iterator it = usrManagerMap.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry pair = (Map.Entry) it.next();
			String keyUserUD = (String) pair.getKey();
			HashMap<String, String> valueMap = (HashMap<String, String>) pair.getValue();

			String phoneBookSupId = valueMap.get("phoneBookSupId");
			String oimManager = valueMap.get("oimManager");
			String userLogin = valueMap.get("userLogin");

			if (!isNullOrEmpty(phoneBookSupId)) {
				SearchCriteria criteria1 = new SearchCriteria(UserManagerConstants.AttributeName.USER_LOGIN.getId(),
						phoneBookSupId, SearchCriteria.Operator.EQUAL);

				try {
					List<User> list1 = usrmgr.search(criteria1, null, null);
					m_logger.finest("search user list in OIM for superwiserid in PhoneBook [" + list1 + "]");
					if (list1.isEmpty()) {
						m_logger.finest("No users found in OIM with user login - +[" + phoneBookSupId + "]");
						createUser(phoneBookSupId, valueMap, createUserInOrg);
						// mdify users manager with created user
						SearchCriteria criteria11 = new SearchCriteria(
								UserManagerConstants.AttributeName.USER_LOGIN.getId(), phoneBookSupId,
								SearchCriteria.Operator.EQUAL);
						m_logger.finest("Search for user in OIM with user login - +[" + phoneBookSupId + "]");
						List<User> list2 = usrmgr.search(criteria11, null, null);
						m_logger.finest("search user list in OIM for superwiserid in PhoneBook after creating user["
								+ list2 + "]");
						if (list2.isEmpty()) {
							m_logger.finest("No users found in OIM with user login after creating user- +["
									+ phoneBookSupId + "]");
						}

						else {
							for (User usr3 : list2) {
								m_logger.finest(
										"User found in OIM after created with user login - +[" + phoneBookSupId + "]");
								modifyUser(keyUserUD, usr3);
							}
						}
					} else {
						for (User usr : list1) {
							m_logger.finest("User found in OIM with user login - +[" + phoneBookSupId + "]");
							if (phoneBookSupId.equalsIgnoreCase(oimManager)) {
								m_logger.finest("Manager in OIM [" + oimManager + "] is same as in phonebook ["
										+ phoneBookSupId + "]");
							} else {
								// mdify user
								m_logger.finest("Manager in OIM [" + oimManager + "] is not same as in phonebook ["
										+ phoneBookSupId + "] updating OIM manager");
								String userStatus = (String) usr
										.getAttribute(UserManagerConstants.AttributeName.STATUS.getId());
								m_logger.finest("Manager status in OIM [" + userStatus + "]");
								if ((!isNullOrEmpty(userStatus)
										&& UserManagerConstants.AttributeValues.USER_STATUS_ACTIVE.getId()
												.equalsIgnoreCase(userStatus))) {
									modifyUser(keyUserUD, usr);
								} else {
									m_logger.finest("User is Disabled in OIM with User Login- ["
											+ (String) usr
													.getAttribute(UserManagerConstants.AttributeName.USER_LOGIN.getId())
											+ "]");
									StringBuffer sfIdString = new StringBuffer();
									sfIdString.append("User "
											+ (String) usr
													.getAttribute(UserManagerConstants.AttributeName.USER_LOGIN.getId())
											+ " is the manager for user " + userLogin
											+ " but he/she is disabled in OIM.");
									if (!sendAdminNotification(sfIdString.toString())) {
										m_logger.severe("Can't send admin notification");
									}
								}
							}
						}
					}

				} catch (UserSearchException e) {
					m_logger.finest("Error is getting while updating user info for user:[" + keyUserUD + "]");
					e.printStackTrace();
				} catch (Exception e) {
					m_logger.finest("Error is gerring while updating user info for user:[" + keyUserUD + "]");
					e.printStackTrace();
				}
			}else{
                m_logger.finest("SupervisorId in phoneBook is null or empty.");
            }
		}
		m_logger.exiting(TAG, "processUserManagerData");
	}

	/**
	 * This method creates user in OIM in geven organization with given userid
	 * and other data.
	 * 
	 * @param manageruserId
	 * @param valueMap
	 * @param createUserInOrg
	 */
	private void createUser(String manageruserId, HashMap<String, String> valueMap, String createUserInOrg) {
		m_logger.entering(TAG, "createUser");
		// create user
		long orgIdLong = 0L;
		OrganizationManager orgMgr = Platform.getService(OrganizationManager.class);
		try {
			Organization organization = orgMgr.getDetails(createUserInOrg, null, true);
			orgIdLong = (Long) organization.getAttribute(OrganizationManagerConstants.AttributeName.ID_FIELD.getId());
			m_logger.finest("orgIdLong == " + orgIdLong);

		} catch (OrganizationManagerException e) {
			m_logger.finest("Exception :" + e);
		} catch (Exception e) {
			m_logger.finest("Exception 1:" + e);
		}
		m_logger.finest("valueMap - +[" + valueMap + "]");
		HashMap<String, Object> userAttributeValueMap = new HashMap<String, Object>();
		userAttributeValueMap.put(UserManagerConstants.AttributeName.USER_LOGIN.getId(), manageruserId);
		userAttributeValueMap.put(UserManagerConstants.AttributeName.FIRSTNAME.getId(), valueMap.get("firstName"));
		userAttributeValueMap.put(UserManagerConstants.AttributeName.LASTNAME.getId(), valueMap.get("lastName"));
		userAttributeValueMap.put(UserManagerConstants.AttributeName.PASSWORD.getId(), "DEVPass1");
		userAttributeValueMap.put(UserManagerConstants.AttributeName.USER_ORGANIZATION.getId(), orgIdLong);
		userAttributeValueMap.put(UserManagerConstants.AttributeName.EMPTYPE.getId(), "EMP");
		m_logger.finest("new");
		m_logger.finest("userAttributeValueMap - +[" + userAttributeValueMap + "]");
		User user = new User(manageruserId, userAttributeValueMap);
		try {
			usrmgr.create(user);
			m_logger.finest("\n" + manageruserId + " User got created ....");
		} catch (ValidationFailedException e) {
			m_logger.finest("ValidationFailedException - +[" + e + "]");
			e.printStackTrace();
		} catch (UserAlreadyExistsException e) {
			m_logger.finest("UserAlreadyExistsException - +[" + e + "]");
			e.printStackTrace();
		} catch (UserCreateException e) {
			m_logger.finest("UserCreateException - +[" + e + "]");
			e.printStackTrace();
		}
		m_logger.exiting(TAG, "createUser");
	}

	/**
	 * This method assign the manager to user as in Phonebook.
	 * 
	 * @param keyUserUD
	 * @param user
	 */
	private void modifyUser(String keyUserUD, User user) {
		m_logger.entering(TAG, "modifyUser");
		UserManagerResult result = null;
		Long usermanagerKey = null;
		usermanagerKey = (Long) user.getAttribute("usr_key");
		m_logger.finest("usermanagerKey is [" + usermanagerKey + "]");
		HashMap<String, Object> attriMap = new HashMap<String, Object>();
		attriMap.put("usr_manager_key", usermanagerKey);
		m_logger.finest("keyUserUDis [" + keyUserUD + "]");
		m_logger.finest("attriMap is [" + attriMap + "]");
		User UserModify = new User(keyUserUD, attriMap);
		try {
			result = usrmgr.modify(UserModify);
		} catch (Exception e) {
			// Catch the exception and process the next user
			m_logger.finest("Error is gerring while updating user user:[" + keyUserUD + "]");
			e.printStackTrace();
		}
		m_logger.exiting(TAG, "modifyUser");
	}

	/**
	 * Sends notification to admin
	 * 
	 * @param strNotification
	 *            String message to send
	 * @return true on success
	 */
	private boolean sendAdminNotification(String strNotification) {
		m_logger.entering(TAG, "sendNotification", strNotification);
		try {
			String strUsrLogin = getUserToNotify();
			if (strUsrLogin == null) {
				m_logger.severe("User to notify on org creation is not set");
				return false;
			}
			m_logger.fine("User to notify: " + strUsrLogin);

			String[] arTo = { strUsrLogin };

			String strTemplate = (lookupOperationsIntf.getDecodedValueForEncodedValue(CONFIGLOOKUP,
					CONFIGWARNINGTEMPLATE));
			if (strTemplate == null || strTemplate.length() == 0) {
				m_logger.severe("Notification template for warnings is not set");
				return false;
			}

			m_logger.finest("Preparing notification event");
			NotificationEvent event = new NotificationEvent();
			event.setTemplateName(strTemplate);
			event.setUserIds(arTo);
			event.setSender(null);

			// attach data
			HashMap<String, Object> map = new HashMap<String, Object>();
			map.put("message", strNotification);
			map.put("act_key", 1);
			event.setParams(map);

			try {
				srvNotification.notify(event);
				m_logger.finest("Notification sent");
			} catch (UserDetailsNotFoundException | NotificationResolverNotFoundException | NotificationException
					| MultipleTemplateException | TemplateNotFoundException | UnresolvedNotificationDataException
					| EventException e) {
				m_logger.log(Level.SEVERE, "Exception sending notification", e);
				return false;
			}

			m_logger.exiting(TAG, "sendAdminNotification");

		} catch (Exception e) {
			m_logger.log(Level.SEVERE, "Exception sending notification", e);
			return false;

		} finally {
			if (lookupOperationsIntf != null)
				lookupOperationsIntf.close();
		}
		return true;
	}

	/**
	 * Reads user login from a configuration lookup
	 * 
	 * @return User login or empty string on failure
	 */
	private String getUserToNotify() {
		m_logger.entering(TAG, "getUserToNotify");
		String strUsrLogin = null;
		try {
			strUsrLogin = (lookupOperationsIntf.getDecodedValueForEncodedValue(CONFIGLOOKUP,
					CONFIGORGCREATEUSERTONOTIFY));
			if (strUsrLogin == null) {
				m_logger.severe("User to notify on org creation is not set");
				return "";
			}
		} catch (Exception e) {
			m_logger.log(Level.SEVERE, "Exception sending notification", e);

		} finally {
			if (lookupOperationsIntf != null)
				lookupOperationsIntf.close();
		}
		m_logger.exiting(TAG, "getUserToNotify", strUsrLogin);
		return strUsrLogin;
	}
}
