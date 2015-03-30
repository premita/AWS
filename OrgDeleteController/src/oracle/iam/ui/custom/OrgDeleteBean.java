package oracle.iam.ui.custom;

import java.io.Serializable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.faces.application.FacesMessage;
import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;

import javax.faces.event.ActionEvent;

import oracle.adf.view.rich.component.rich.data.RichTable;
import oracle.adf.view.rich.context.AdfFacesContext;
import oracle.adf.view.rich.event.DialogEvent;

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
import oracle.iam.ui.platform.model.common.OIMClientFactory;

import oracle.jbo.uicli.binding.JUCtrlHierNodeBinding;


public class OrgDeleteBean implements Serializable {
  @SuppressWarnings("compatibility:-4547437263090366186")
  private static final long serialVersionUID = 5588278241723978133L;
  private UIComponent parentPanel;
  
  public OrgDeleteBean() {
    super();
  }

  public void delete(DialogEvent ev) {
    final Logger logger = Logger.getLogger("com.icsynergy");
    logger.entering(this.getClass().getCanonicalName(), "delete");
    
    if (ev.getOutcome().compareTo(DialogEvent.Outcome.no) == 0) {
      return;
    }
    
    FacesContext facesContext = FacesContext.getCurrentInstance();

    RichTable table = (RichTable)findComponent(parentPanel, "t1");

    Iterator selection = table.getSelectedRowKeys().iterator();

    while (selection.hasNext()) {
      table.setRowKey(selection.next());

      JUCtrlHierNodeBinding rowData = (JUCtrlHierNodeBinding) table.getRowData();
      
      String strOrgName = String.valueOf(rowData.getRow().getAttribute("organizationName"));
    
      FacesMessage fm;

      try {
        if (!deleteOrganization(strOrgName)) {
          fm =
            new FacesMessage("Organization: " + strOrgName + " has not been deleted");
          fm.setSeverity(FacesMessage.SEVERITY_WARN);
        } else {
          fm =
            new FacesMessage("Organization: " + strOrgName + " has been deleted");
          fm.setSeverity(FacesMessage.SEVERITY_INFO);
        }
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Exception deleting organization " + strOrgName, e);
        fm = 
          new FacesMessage("Exception deleting Organization: " + strOrgName + 
                           ". " + e.getMessage());
        fm.setSeverity(FacesMessage.SEVERITY_ERROR);
      }

      facesContext.addMessage(null, fm);
    }
    
    // refresh table
    AdfFacesContext.getCurrentInstance().addPartialTarget(table);
    
    logger.exiting(this.getClass().getCanonicalName(), "delete");
  }

  private boolean deleteOrganization(String strOrgName) throws Exception {
    final Logger logger = Logger.getLogger("com.icsynergy");
    logger.entering(this.getClass().getCanonicalName(), 
                    "deleteOrganization", strOrgName);
    
    if (strOrgName.isEmpty()) {
      logger.warning("Organization name is empty. Exiting...");
      throw new Exception("Organization name is empty");
    }
    
    logger.finest("Getting Organization Manager...");
    OrganizationManager orgMgr = OIMClientFactory.getOrganizationManager();

    Organization org;
    try {
      logger.finest("Getting Organization details...");
      org = orgMgr.getDetails(strOrgName, null, true);
    } catch (OrganizationManagerException e) {
      throw new Exception("Can't find an organization with name: " + strOrgName);
    }
    
    // get OrgUserRelationship list for this organization
    List<OrgUserRelationship> lstUserMembers;
    
    try {
      logger.finest("Getting Organization members...");
      lstUserMembers =
             orgMgr.getOrganizationMembersRelations(org.getEntityId(),
                                            null, null, null);
      logger.finest(lstUserMembers.toString());
    } catch (OrganizationManagerException e) {
      throw new Exception(e.getErrorMessage());
    }
    
    logger.finest("Getting User Manager...");
    // for each user with HOME relationship -> delete the user
    UserManager usrMgr = OIMClientFactory.getUserManager();
    for (OrgUserRelationship rel : lstUserMembers) {
      if (rel.getRelationType() == OrgUserRelationship.RelationType.HOME) {
        logger.finest(String
                      .format("User %s is %s", 
                              rel.getUser(), rel.getRelationType().toString()));

        usrMgr.delete(rel.getUser().getEntityId(), false);
        logger.finer("User has been deleted: " + rel.getUser().getLogin());
      }
    }
    
    logger.finest("Getting Role Manager...");
    RoleManager roleMgr = OIMClientFactory.getRoleManager();

    Role role = null;
    List<User> lstUsr = null;
    Set<String> setUserKeys = null;

    // get a list of users for a role "Display Name" == "Org Name"
    try {
      logger.finest("Getting Role details...");
      role =
           roleMgr.getDetails(RoleManagerConstants
                              .RoleAttributeName.DISPLAY_NAME.getId(),
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
      
        if ("COMPLETED"
              .equalsIgnoreCase(roleMgr
                                .revokeRoleGrant(role.getEntityId(), 
                                                 setUserKeys)
                                .getStatus())) {
          logger.finest("Role has been revoked from users: " 
                        + role.getDisplayName());
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
    
    logger.finest("Processing admin role...");
    // get a list of users for a role "Display Name" == "Org Name Admin"
    try {
      logger.finest("Getting Role details...");
      role =
        roleMgr.getDetails(RoleManagerConstants
                           .RoleAttributeName.DISPLAY_NAME.getId(), 
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

        if ("COMPLETED".equalsIgnoreCase(roleMgr
                                         .revokeRoleGrant(role.getEntityId(), 
                                                          setUserKeys)
                                         .getStatus())) {
          logger.finest("Role has been revoked from users: " +
                        role.getDisplayName());
        }
      }

      // delete hierarchy for AWS Admin role
      logger.finest("Removing role hierarchy relationship...");
      if (roleMgr
          .removeRoleRelationship(RoleManagerConstants
                                .RoleAttributeName.NAME.getId(), 
                                "aws_delegated_admin_parent",
                                RoleManagerConstants
                                .RoleAttributeName.DISPLAY_NAME.getId(), 
                                strOrgName + " Admin")
          .getStatus().equals("COMPLETED")) {
        logger.finest("Role hierarchy has been successfully removed");
      } else {
        throw new Exception("Can't remove admin role relationship: " 
                            + strOrgName + " Admin");
      }

      // delete the aws admin role
      if ("COMPLETED".
            equalsIgnoreCase(roleMgr 
                             .delete(RoleManagerConstants
                                     .RoleAttributeName.DISPLAY_NAME.getId(), 
                                     strOrgName + " Admin")
                             .getStatus())) {
        logger.finest("Role successfully deleted: " + strOrgName + " Admin");
      } else {
        throw new Exception("Can't delete role: " + strOrgName + " Admin");
      }
    } catch (NoSuchRoleException nsre) {
      logger.warning("Role: " + strOrgName + " Admin not found");
    }
    
    // clear membership rule
    logger.finest("Clearing user membership rule...");
    if (!"SUCCESS"
          .equalsIgnoreCase(orgMgr 
                            .setUserMembershipRule(org.getEntityId(), null)))
      throw new Exception("Can't clear organization membership rule");
    logger.finest("Membership rule cleared");
    
    // delete the organization
    orgMgr.delete(strOrgName, true);
    logger.fine("Organization deleted: " + strOrgName);

    logger.exiting(this.getClass().getCanonicalName(), 
                    "deleteOrganization", strOrgName);

    return true;
  }
  
  /**
   * Locate an UIComponent from its root component.
   * @param base root Component (parent)
   * @param id UIComponent id
   * @return UIComponent object
   */
  public static UIComponent findComponent(UIComponent base, String id) {
    if (id.equals(base.getId()))
      return base;

    UIComponent children = null;
    UIComponent result = null;
    Iterator childrens = base.getFacetsAndChildren();
    while (childrens.hasNext() && (result == null)) {
      children = (UIComponent)childrens.next();
      if (id.equals(children.getId())) {
        result = children;
        break;
      }
      result = findComponent(children, id);
    }
    return result;
  }

  public void setParentPanel(UIComponent parentPanel) {
    this.parentPanel = parentPanel;
  }

  public UIComponent getParentPanel() {
    return parentPanel;
  }
}
