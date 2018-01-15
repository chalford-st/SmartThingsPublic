/**
 *  Copyright 2015 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

metadata {
    definition (name: "Osram Lightify Switch Mini", namespace: "chalford", author: "Charlie Halford", ocfDeviceType: "oic.d.light") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "Switch Level"
        capability "Health Check"

        fingerprint profileId: "0104", deviceId: "0810", inClusters: "0000, 0001, 0020, 1000, FD00", outClusters: "0003, 0004, 0005, 0006, 0008, 0019, 0300, 1000", manufacturer: "OSRAM", model: "Lightify Switch Mini", deviceJoinName: "Lightify Switch Mini"
    }

    tiles(scale: 2) {
        multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true){
            tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "on", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#00A0DC", nextState:"turningOff"
                attributeState "off", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
                attributeState "turningOn", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#00A0DC", nextState:"turningOff"
                attributeState "turningOff", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
            }
            tileAttribute ("device.level", key: "SLIDER_CONTROL") {
                attributeState "level", action:"switch level.setLevel"
            }
        }
        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        main "switch"
        details(["switch", "refresh"])
    }
    
    simulator {
    	status "move-up":  "catchall: 0104 0008 01 01 0140 00 C206 01 00 0000 05 00 0026"
        status "move-up":  "catchall: 0104 0008 01 01 0140 00 C206 01 01 0000 05 00 0026"
        status "stop-moving": "catchall: 0104 0008 01 01 0140 00 C206 01 00 0000 03 00"
    }
}

// Parse incoming device messages to generate events
def parse(String description) {
    log.debug "description is $description"

    if (description?.startsWith("catchall:")) {
        def descMap = zigbee.parseDescriptionAsMap(description)
        log.debug "Source endpoint: ${descMap.sourceEndpoint}"
        log.debug "Destination endpoint: ${descMap.destinationEndpoint}"
        def cluster = descMap.clusterInt
        def command = descMap.commandInt
        def payload = descMap.payload
        if (cluster == 0x0006) {
        	switch(command) {
            	case 0x00:
                	log.debug "Off command issued"
                	off()
                    break
        		case 0x01:
                	log.debug "On command issued"
                    on()
                    break
                default:
                	log.warn "Unsupported command ($descMap.command) issued for cluster $descMap.clusterId"
                    break
            }
        } else if (cluster == 0x0008) {
        	switch(command) {
        		case 0x01:
                    log.debug "Move command issued"
                    fireMoveEvents(descMap.data, false)
                    break
                case 0x03:
                    log.debug "Stop command issued"
                    state.moving = false
                    fireStopEvents()
                    break
                case 0x04:
                    log.debug "Move to level command issued"
                    fireMoveToLevelEvents(descMap.data, true)
                    break
                case 0x05:
                    log.debug "Move with On/Off command issued"
                    fireMoveEvents(descMap.data, true)
                    break
                default:
                	log.debug "Unsupported command ($descMap.command) issued for cluster $descMap.clusterId"
                    break
            }
        } else if (cluster == 0x0300) {
        	switch(command) {
            	case 0x0A:
                	log.debug "UNSUPPORTED: Move to colour temperature command issued, payload is: $descMap.data"
                    break
                default:
                	log.debug "Unsupported command ($descMap.command) issued for cluster $descMap.clusterId"
                    break
            }
        } else if (cluster == 0x8021) {
        	log.debug "UNSUPPORTED: Bind response issued, command is: $command, payload is: $payload"
        } else {
            log.warn "DID NOT PARSE MESSAGE for description : $description"
            log.debug "${descMap}"
        }
    }
}

def sendEvents() {
	Integer level = (state.level ?: 0) * 100 / 0xFE
	def sw = state.switch ? "on" : "off"
    log.debug "Sending events for switch: $sw, and level: $level"
	sendEvent(name: "level", value: level)
	sendEvent(name: "switch", value: sw)
}

def fireMoveEvents(commandPayload, withOnOff) {
    def moveDirection = "unknown"
    if(commandPayload[0] && commandPayload[0] == "00") {
        moveDirection = "up"
    } else if(commandPayload[0] && commandPayload[0] == "01") {
        moveDirection = "down"
    }
    def rate = 0
    if(commandPayload[1]) {
        rate = zigbee.convertHexToInt(commandPayload[1])
    }
    log.debug "Start dimming $moveDirection at $rate units per second, with on/off: $withOnOff"
    state.velocity = moveDirection == "up" ? rate : rate * -1
    if(withOnOff) {
    	if(state.velocity > 0) {
        	state.switch = 1
        } else if(state.velocity < 0 && state.level == 0x00) {
			state.switch = 0
        }
    }
    moveHandler()
}

def moveHandler() {
    state.level = state.level ?: 0
    def newLevel = state.level + state.velocity
    if(newLevel < 0x00) {
        state.level = 0x00
    } else if(newLevel > 0xFE) {
        state.level = 0xFE
    } else {
        state.level = newLevel
    }
	sendEvents()
	if(state.velocity != 0) {
    	runIn(1, moveHandler)
    }
}

def fireStopEvents() {
    log.debug "Stop dimming"
    state.velocity = 0
}

def fireMoveToLevelEvents(commandPayload, withOnOff) {
    def level = 0
    if(commandPayload[0]) {
        level = zigbee.convertHexToInt(commandPayload[0])
    }
    def transitionTime = 0
    if(commandPayload[1]) {
    	//read that zigbee is little endian, so need to reverse octet order
        transitionTime = zigbee.convertHexToInt(commandPayload[2] + commandPayload[1])
    }
    def transitionSeconds = transitionTime / 10
    log.debug "Dimming to $level in $transitionSeconds seconds, on/off: $withOnOff"
    state.level = level
    if(withOnOff) {
    	if(state.level <= 0) {
        	state.switch = 0
        } else {
			state.switch = 1
        }
    }
    sendEvents()
}

def off() {
	log.debug "Executing 'off'"
	state.switch = 0
	state.velocity = 0
	sendEvents()
}

def on() {
	log.debug "Executing 'on'"
	state.switch = 1
	state.velocity = 0
	if(!state.level) {
		state.level = 0xFE
	}
	sendEvents()
}

def setLevel(value) {
	log.debug "Executing 'setLevel'"
	state.level = value * 0xFE / 100
	sendEvents()
}
/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
    return zigbee.onOffRefresh()
}

def refresh() {
    zigbee.onOffRefresh() + zigbee.levelRefresh()
}

def configure() {
    log.debug "Configiring Reporting and Bindings."
    def configCmds = []

    configCmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}" // battery
    configCmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0006 {${device.zigbeeId}} {}" // on/off changes
    configCmds += "zdo bind 0x${device.deviceNetworkId} 0x02 0x01 0x0006 {${device.zigbeeId}} {}" // on/off changes
    configCmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0008 {${device.zigbeeId}} {}" // intensity changes
    configCmds += "zdo bind 0x${device.deviceNetworkId} 0x02 0x01 0x0008 {${device.zigbeeId}} {}" // intensity changes
    configCmds += "zdo bind 0x${device.deviceNetworkId} 0x03 0x01 0x0008 {${device.zigbeeId}} {}" // intensity changes
    configCmds += "delay 2000"

    return configCmds + refresh()
}