package net.floodlightcontroller.elephantrouting.webroutable;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.restserver.RestletRoutable;

public class ElephantRoutingWebRoutable implements RestletRoutable{

	@Override
	public Restlet getRestlet(Context context) {
		Router router =new Router(context);
		router.attach("/elephants/add", ElephantResource.class);
		return router;
	}

	@Override
	public String basePath() {
		return "/elephantRouting";
	}

}
