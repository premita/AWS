// Decompiled by Jad v1.5.8g. Copyright 2001 Pavel Kouznetsov.
// Jad home page: http://www.kpdus.com/jad.html
// Decompiler options: packimports(3) 
// Source File Name:   CreateUserSetOrganizationPreProcessHandler.java

package com.icsynergy.processplugins;

import Thor.API.Exceptions.tcAPIException;
import Thor.API.Exceptions.tcColumnNotFoundException;
import Thor.API.Operations.tcOrganizationOperationsIntf;
import Thor.API.tcResultSet;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.iam.platform.Platform;
import oracle.iam.platform.kernel.spi.PreProcessHandler;
import oracle.iam.platform.kernel.vo.*;

public class CreateUserSetOrganizationPreProcessHandler
    implements PreProcessHandler
{
    private final static Logger logger = Logger.getLogger("com.icsynergy");
    private final static String TAG = 
      CreateUserSetOrganizationPreProcessHandler.class.getCanonicalName();
    
    public void initialize(HashMap hashmap)
    {
    }

    public boolean cancel(long processId, long eventId, AbstractGenericOrchestration abstractgenericorchestration)
    {
        return false;
    }

    public void compensate(long l, long l1, AbstractGenericOrchestration abstractgenericorchestration)
    {
    }

    public EventResult execute(long processId, long eventId, Orchestration orch)
    {
        logger.entering(TAG, "execute");
        String operation = orch.getOperation();
        
        if(!operation.equalsIgnoreCase("CREATE"))
        {
            logger.finer("Not the create operation, exiting");
            return new EventResult();
        }
        
        HashMap changedParameters = orch.getParameters();
        System.out.println((new StringBuilder("ChangedParameters: "))
                           .append(changedParameters).toString());
        
        if(!changedParameters.containsKey("act_key"))
        {
            if(!changedParameters.containsKey("org_pin"))
            {
                logger.warning("org_pin not found and act_key also not set. Exiting");
                return new EventResult();
            }
            setRoleToEmployeeOnSelfReg(orch, changedParameters);
            setOrgAndMgmtGrpIdsOnSelfReg(orch, changedParameters);
        }
        return new EventResult();
    }

    private void setMgmtGrpIdsOnAdminCreated(Orchestration orch, HashMap changedParameters)
    {
        System.out.println((new StringBuilder())
                           .append("CreateUserSetOrganizationPreProcessHandler:Entering setMgmtGrpIdsOnAdminCreated : ")
                           .append(changedParameters).toString());
        
        String org_act_key = changedParameters.get("act_key").toString();
        
        tcOrganizationOperationsIntf orgAPI = 
          Platform.getService(tcOrganizationOperationsIntf.class);
        
        Hashtable org_srch_map = new Hashtable();
        org_srch_map.put("Organizations.Key", 
                         Long.valueOf(Long.parseLong(org_act_key)));
        System.out.println((new StringBuilder()).append("looking for org id: ")
                           .append(org_act_key).toString());
        
        try
        {
            tcResultSet orgsrc_result = orgAPI.findOrganizations(org_srch_map);
            System.out.println((new StringBuilder()).append("result set = ")
                               .append(orgsrc_result).toString());
            
            int rowCount = orgsrc_result.getRowCount();
            System.out.println((new StringBuilder()).append("row count = ")
                               .append(rowCount).toString());
            
            if(rowCount != 0)
            {
                orgsrc_result.goToRow(0);
                String org_id = orgsrc_result.getStringValue("ORG_UDF_GRP_ID");
                System.out.println((new StringBuilder()).append("Found org id: ")
                                   .append(org_id).append(", Organization: ")
                                   .append(org_act_key));

                orch.addParameter("AWSMgmtGrpIDs", org_id);
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
        System.out.println((new StringBuilder())
                           .append("CreateUserSetOrganizationPreProcessHandler:Exiting setMgmtGrpIdsOnAdminCreated : ")
                           .append(changedParameters).toString());
    }

    private void setOrgAndMgmtGrpIdsOnSelfReg(Orchestration orch, HashMap changedParameters)
    {
        logger.entering(TAG, "setOrgAndMgmtGrpIdsOnSelfReg", 
                        changedParameters.toString());
        
        String org_pin = changedParameters.get("org_pin").toString();
        
        tcOrganizationOperationsIntf orgAPI = 
          Platform.getService(tcOrganizationOperationsIntf.class);
        
        Hashtable org_srch_map = new Hashtable();
        org_srch_map.put("pin", org_pin);
        
        try
        {
            tcResultSet orgsrc_result = orgAPI.findOrganizations(org_srch_map);
            logger.finest("result set = " + orgsrc_result.toString());

            int rowCount = orgsrc_result.getRowCount();
            if(rowCount != 0)
            {
                orgsrc_result.goToRow(0);

                long org_act_key = 
                  Long.parseLong(orgsrc_result.getStringValue("Organizations.Key"));
                String org_id = orgsrc_result.getStringValue("ORG_UDF_GRP_ID");
                // String strOrgName = orgsrc_result.getStringValue("ACT_NAME");
                logger.finest("Found org id: " + org_id + 
                              ", Organization: " + org_act_key);

                orch.addParameter("act_key", Long.valueOf(org_act_key));
            }
        }
        catch(Exception e)
        {
            logger.log(Level.SEVERE, "Exception setting additional params", e);
        }
        
        logger.exiting(TAG, "setOrgAndMgmtGrpIdsOnSelfReg", 
                       orch.getParameters().toString());
    }

    private void setRoleToEmployeeOnSelfReg(Orchestration orch, 
                                            HashMap changedParameters)
    {
        logger.entering(TAG, "setRoleToEmployeeOnSelfReg", 
                        orch.getParameters().toString());
        try
        {
          orch.addParameter("Role", "EMP");
          logger.finest("User Role's set to EMP");
        }
        catch(Exception e)
        {
          logger.log(Level.SEVERE, "Exception setting user's role", e);
        }
        
        logger.exiting(TAG, "setRoleToEmployeeOnSelfReg", 
                       orch.getParameters().toString());
    }

    public BulkEventResult execute(long processId, long eventId, BulkOrchestration arg2)
    {
        System.out.println("CreateUserSetOrganizationPreProcessHandler: BulkEvent execute");
        return new BulkEventResult();
    }

    
}
