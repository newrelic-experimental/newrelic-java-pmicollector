package com.ibm.ws.management;

import javax.management.MBeanServer;

import com.newrelic.agent.bridge.AgentBridge;
import com.newrelic.api.agent.weaver.Weave;
import com.newrelic.api.agent.weaver.Weaver;
import com.newrelic.instrumentation.labs.was.pmi.PMISampler;

@Weave
public abstract class AdminServiceImpl  {

	// Instance variable on the admin service used to pull mbeans
    private MBeanServer _mbServer = Weaver.callOriginal();
    
    // This method is called on WebSphere startup. Since this method is always called
    // we are using this method to grab the instance variable _mbServer.
    public String getJvmType() {

        AgentBridge.privateApi.addMBeanServer(_mbServer);
        PMISampler.StartInstance();
        return Weaver.callOriginal();
    }

}
