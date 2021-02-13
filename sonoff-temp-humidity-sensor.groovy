/*
     *  SONOFF SNZB-02 ZigBee Temperature & Humidity Sensor 
     *
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
    import physicalgraph.zigbee.zcl.DataType

metadata {
    definition(name: "SONOFF SNZB-02", namespace: "mvevitsis", author: "mvevitsis", mnmn:"SmartThings", ocfDeviceType: "oic.d.thermostat", vid: "23c9be50-98c3-34cb-b52f-ecc9fbfe72cc") {
        //use vid: "e89a61ab-bde3-39eb-b9d6-c3b34b25120e" to enable refresh button
        //use vid: "23c9be50-98c3-34cb-b52f-ecc9fbfe72cc" to disable refresh button
        capability "Configuration"
        capability "Battery"
        capability "Refresh"
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"
        capability "Sensor"
        capability "Health Check"
        
        fingerprint profileId: "0104", inClusters: "0000,0003,0402,0405", outClusters: "0003", model: "TH01", deviceJoinName: "SONOFF Temperature & Humidity Sensor", manufacturer: "eWeLink"
    }

    preferences {
        input "tempOffset", "number", title: "Temperature offset", description: "Select how many degrees to adjust the temperature.", range: "*..*", displayDuringSetup: false
        input "humidityOffset", "number", title: "Humidity offset", description: "Enter a percentage to adjust the humidity.", range: "*..*", displayDuringSetup: false
    }

}

def parse(String description) {
    log.debug "description: $description"

    // getEvent will handle temperature and humidity
    Map map = zigbee.getEvent(description)
    if (!map) {
        Map descMap = zigbee.parseDescriptionAsMap(description)
        log.debug "Parsed Description : $descMap"
		if (descMap?.clusterInt == zigbee.TEMPERATURE_MEASUREMENT_CLUSTER && descMap.commandInt == 0x07) {
            if (descMap.data[0] == "00") {
                log.debug "TEMP REPORTING CONFIG RESPONSE: $descMap"
                sendEvent(name: "checkInterval", value: 60 * 12, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
            } else {
                log.warn "TEMP REPORTING CONFIG FAILED- error code: ${descMap.data[0]}"
            }
        }else if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
			if (descMap.attrInt == 0x0021) {
            			map = getBatteryPercentageResult(Integer.parseInt(descMap.value,16))
			} else {
				map = getBatteryResult(Integer.parseInt(descMap.value, 16))
            		}
		}
    } else if (map.name == "temperature") { 
        map.value = (float) Math.round( (map.value as Float) * 10.0 ) / 10
        if (tempOffset) {
            map.value = (float) map.value + (float) tempOffset
        }
        map.descriptionText = temperatureScale == 'C' ? '{{ device.displayName }} was {{ value }}°C' : '{{ device.displayName }} was {{ value }}°F'
        map.translatable = true
    } else if (map.name == "humidity") {
        if (humidityOffset) {
            map.value = (int) map.value + (int) humidityOffset
        }
    }

    log.debug "Parse returned $map"
    return map ? createEvent(map) : [:]
}

def installed() {
	log.debug "Device installed..."
	configure()
}

def updated() {
	log.debug "Device updated..."
	configure()
}

def ping() {
    return refresh()
}

def refresh() {
    log.debug "Device refresh requested..."
    return zigbee.readAttribute(zigbee.RELATIVE_HUMIDITY_CLUSTER, 0x0000) +
           zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000) +
		   zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) +
           zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021) 

}

def getBatteryPercentageResult(rawValue) {
	log.debug "Battery Percentage rawValue = ${rawValue} -> ${rawValue / 2}%"
	def result = [:]

	if (0 <= rawValue && rawValue <= 200) {
		result.name = 'battery'
		result.translatable = true
		result.value = Math.round(rawValue / 2)
		result.descriptionText = "${device.displayName} battery was ${result.value}%"
	}

	return result
}

private Map getBatteryResult(rawValue) {
	def volts = rawValue / 10
	log.debug "Battery voltage rawValue = ${rawValue} -> ${volts}v"
}

def configure() {
    // Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
    sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 1 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])

    log.debug "Configuring reporting intervals..."

    // Default minReportTime 30 seconds, maxReportTime 1 hour
    return refresh() +
           zigbee.configureReporting(zigbee.RELATIVE_HUMIDITY_CLUSTER, 0x0000, DataType.UINT16, 30, 3600, 100) +
           zigbee.configureReporting(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000, DataType.UINT16, 30, 3600, 10) +
           zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20, DataType.UINT16, 30, 3600, 0x01) +
           zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x21, DataType.UINT16, 30, 3600, 0x01) 

}
