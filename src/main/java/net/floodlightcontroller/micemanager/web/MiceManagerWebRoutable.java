package net.floodlightcontroller.micemanager.web;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.restserver.RestletRoutable;

public class MiceManagerWebRoutable implements RestletRoutable {
	
	    @Override
	    public Restlet getRestlet(Context context) {
	        Router router = new Router(context);
	        router.attach("/mice", MiceResource.class); // POST
	        return router;
	    }

	    @Override
	    public String basePath() {
	        return "/trafficManager";
	    }
	}