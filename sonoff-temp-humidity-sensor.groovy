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
        input "tempMaxReportTime", "number", title: "Temperature Maximum Report Time", description: "Maximum number of seconds between temperature reports (default 300 = 5 min)", range: "60..3600", displayDuringSetup: false
		input "tempReportableChange", "number", title: "Temperature Reportable Change", description: "Amount of change needed to trigger a temperature report (default 10 = 0.1°)", range: "10..1000", displayDuringSetup: false
		input "humidityMaxReportTime", "number", title: "Humidity Maximum Report Time", description: "Maximum number of seconds between humidity reports (default 300 = 5 min)", range: "60..3600", displayDuringSetup: false
		input "humidityReportableChange", "number", title: "Humidity Reportable Change", description: "Amount of change needed to trigger a humidity report (default 100 = 1%)", range: "10..1000", displayDuringSetup: false
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
			} else {
                log.warn "TEMP REPORTING CONFIG FAILED- error code: ${descMap.data[0]}"
            }
        } else if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
			if (descMap.attrInt == 0x0021) {
            			map = getBatteryPercentageResult(Integer.parseInt(descMap.value, 16))
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
    //initialize capabilities 
    sendEvent(name: 'temperature', value: 0, unit: 'C', displayed: false)
    sendEvent(name: 'humidity', value: 100, unit: '%', displayed: false)
    sendEvent(name: 'battery', value: 50, unit: '%', displayed: false)
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
    return zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000) +
           zigbee.readAttribute(zigbee.RELATIVE_HUMIDITY_CLUSTER, 0x0000) +
		   zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) + //battery voltage
           zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021)   //battery percentage

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
    //device reports percentage, so volts is unused 
}

def configure() {
	// Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
	sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 1 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])

	// temperature minReportTime 30 seconds, maxReportTime 5 min by default
    // humidity minReportTime 30 seconds, maxReportTime 5 min by default
	// battery minReportTime 30 seconds, maxReportTime 1 hr by default
    def tempTime = tempMaxReportTime ? tempMaxReportTime : 300
    def tempChange = tempReportableChange ? tempReportableChange : 10
    def humidityTime = humidityMaxReportTime ? humidityMaxReportTime : 300
	def humidityChange = humidityReportableChange ? humidityReportableChange : 100
	log.debug "Configuring reporting intervals..."
    return refresh() +
           zigbee.configureReporting(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000, DataType.UINT16, 30, tempTime, tempChange) + //default reportableChange 10 = 0.1°
		   zigbee.configureReporting(zigbee.RELATIVE_HUMIDITY_CLUSTER, 0x0000, DataType.UINT16, 30, humidityTime, humidityChange) + //default reportableChange 100 = 1%
           zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020, DataType.UINT8, 30, 3600, 0x01) + //default reportableChange 1 = 0.1v
           zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021, DataType.UINT8, 30, 3600, 0x02) //default reportableChange 2 = 1%
}
