package net.floodlightcontroller.elephantrouting.webroutable;

import java.io.IOException;

import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.MappingJsonFactory;

public class ElephantResource extends ServerResource{
	 protected static Logger log = LoggerFactory.getLogger(ElephantResource.class);
	 
	 public class ElephantDefinition {
		 public String srcMAC = null;
		 public String dstMAC = null;
		 public String srcPort = null;
		 public String dstPort = null;
		 
	 }
	 protected void jsonToElephantDefinition(String json, ElephantDefinition elephant) throws IOException{
		 MappingJsonFactory f = new MappingJsonFactory();
	        JsonParser jp;
	        try {
	            jp = f.createJsonParser(json);
	        } catch (JsonParseException e) {
	            throw new IOException(e);
	        }
	        
	        jp.nextToken();
	        if (jp.getCurrentToken() != JsonToken.START_OBJECT) {
	            throw new IOException("Expected START_OBJECT");
	        }
	 }
}
