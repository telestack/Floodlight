/**
 *    Copyright 2015, Big Switch Networks, Inc.
 *    Originally created by Pengfei Lu, Network and Cloud Computing Laboratory, Dalian University of Technology, China 
 *    Advisers: Keqiu Li and Heng Qi 
 *    This work is supported by the State Key Program of National Natural Science of China(Grant No. 61432002) 
 *    and Prospective Research Project on Future Networks in Jiangsu Future Networks Innovation Institute.
 *    
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may 
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *    
 *         http://www.apache.org/licenses/LICENSE-2.0 
 *    
 *    Unless required by applicable law or agreed to in writing, software 
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.circuittree;

import java.util.List;

import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.routing.Route;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;

public interface ICircuitTreeService extends IFloodlightService {
	
	public void addNewMice(String ipadd);
	public void handleNewMice(IDevice device);
	public void egressSwitchFlows(DatapathId swid, MacAddress serverMac);
	public OFFlowMod.Builder flowMod(EthType ethType, MacAddress macAddress,
			IPv4Address ip, OFPort forPort, MacAddress serverMac, int matchType,String identifier);
	public List<DatapathId> accessSwitches(SwitchPort[] ap);
	public Route findRoute(DatapathId longSrcDpid, OFPort shortSrcPort,
			DatapathId longDstDpid, OFPort shortDstPort) ;
	public IPv4Address queryServer(IPv4Address ip);

}
