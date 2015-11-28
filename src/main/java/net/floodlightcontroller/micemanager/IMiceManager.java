package net.floodlightcontroller.micemanager;

import java.util.List;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;

import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.routing.Route;

public interface IMiceManager extends IFloodlightService{
	public void handleNewMice(String ipadd);
	public void EgressSwitchFlows(DatapathId swid);
	public OFFlowMod.Builder flowMod(EthType ethType, MacAddress macAddress,
			IPv4Address ip, String name, OFPort forPort);
	public List<DatapathId> accessSwitches(SwitchPort[] ap);
	public Route findRoute(DatapathId longSrcDpid, OFPort shortSrcPort,
			DatapathId longDstDpid, OFPort shortDstPort) ;
}
