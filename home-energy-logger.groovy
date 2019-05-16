/**
 *  Electricity Logger
 *
 *  Copyright 2019 Leonard Budney
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software  * Foundation, either version 3 of the License,
 * or (at your option) any later version, along with the following
 * terms:
 *
 * 1. You may convey a work based on this program in accordance
 * with section 5, provided that you retain the above notices.
 *
 * 2. You may convey verbatim copies of this program code as you
 * receive it, in any medium, provided that you retain the above
 * notices.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS    * FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
definition(
    name: "Electricity Logger",
    namespace: "budney",
    author: "Leonard Budney",
    description: "Logs electricity usage along with some other stuff, like the state of the thermostat.",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")


preferences {
    section("Home Energy Monitor") {
    	input "theMeter", "capability.energyMeter", required: true
    }
    section("Thermostat") {
		input "theThermostat", "capability.thermostat", required: true
	}
	section("Thermometers") f
		input "indoorTemp", "capability.temperatureMeasurement", required: false, title: "Indoors"
		input "outdoorTemp", "capability.temperatureMeasurement", required: false, title: "Outdoors"
	}
	section("Hygrometers") f
		input "indoorHumidity", "capability.relativeHumidityMeasurement", required: false, title: "Indoors"
		input "outdoorHumidity", "capability.relativeHumidityMeasurement", required: false, title: "Outdoors"
	}
	section("Check Interval") {
    	input "interval", "number", required: true, title: "Minutes?", submitOnChange: true
    }
	section("Elasticsearch") {
    	input "indexPrefix", "text", required: true, title: "Prefix"
        input "indexHost", "text", required: true, title: "Host:Port"
    }
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize() {
	// Initialize state from the thermostat
    readThermostat()

	// Subscribe to thermostat state changes
    subscribe(theThermostat, "thermostatOperatingState.heating", thermostatEvent)
    subscribe(theThermostat, "thermostatOperatingState.idle", thermostatEvent)
    subscribe(theThermostat, "thermostatOperatingState.cooling", thermostatEvent)
    subscribe(theThermostat, "thermostatMode", thermostatEvent)

    // Subscribe to temperature changes
    subscribe(indoorTemp, "temperature", thermostatEvent)
    subscribe(outdoorTemp, "temperature", thermostatEvent)

    // Subscribe to humidity changes
    subscribe(indoorHumidity, "humidity", thermostatEvent)
    subscribe(outdoorHumidity, "humidity", thermostatEvent)

    // Subscribe to energy meter to detect updates
    subscribe(theMeter, "energy", meterEvent)

    // Update energy consumption state
    refreshMeter()
}

// Generic event handler for the thermostat
def thermostatEvent(evt) {
	readThermostat()
}

// From the thermostat, find out the indoor/outdoor temperatures and
// whether the furnace or AC are running.
def readThermostat() {
    // Humidity
    state.indoorHumidity = indoorHumidity.currentValue("humidity")
    state.outdoorHumidity = outdoorHumidity.currentValue("humidity")

    // Temperature
	state.indoorTemperature = indoorTemp.currentValue("temperature")
    def outdoorTemperature = outdoorTemp.currentValue("temperature")
    if (outdoorTemperature && 212 > outdoorTemperature) {
	    state.outdoorTemperature = outdoorTemperature
    }

    // Check if heat or AC is running:
    if (theThermostat.currentValue("thermostatMode") == "off") {
    	state.heatingState = 0
    	state.coolingState = 0
    }
    else {
    	state.heatingState = theThermostat.currentValue("thermostatOperatingState") == "heating" ? true : false
    	state.coolingState = theThermostat.currentValue("thermostatOperatingState") == "cooling" ? true : false
    }
}

// Refresh the energy meter. When it finishes, the subscribed attributes will cause
// the new reading to be logged.
def refreshMeter() {
	theMeter.refresh()

    // Do it again!
    runIn(60 * interval, refreshMeter)
}

// Generic event handler for the meter
def meterEvent(evt) {
	readMeter(theMeter)
}

// Check energy consumption on the home energy meter, emitting a log
// message giving statistics since the previous reading
def readMeter(meter) {
    // Get the new readings from the meter
    def current = [
    	timestamp: now(),
        energy: meter.currentValue("energy"),
        power: meter.currentValue("power"),
        power1: meter.currentValue("power1"),
        power2: meter.currentValue("power2"),
    ]

    // If we have previous readings, use them
    if (state.lastMeterReadingTimestamp) {
        // Get the readings from this app's state
        def previous = [
        	timestamp: state.lastMeterReadingTimestamp,
            energy: state.lastMeterReadingEnergy,
            power: state.lastMeterReadingPower,
            power1: state.lastMeterReadingPower1,
            power2: state.lastMeterReadingPower2,
        ]

        // Compute useful stats
        def elapsed = current.timestamp - previous.timestamp
        def energy = current.energy - previous.energy
        def consumption = Math.round( 100 * (energy * 30 * 24 * 60 * 60 * 1000 / elapsed) ) / 100
        def minutes = Math.round( 100 * elapsed / (60 * 1000) ) / 100
        def power = Math.round( 100 * energy * 1000 / ( elapsed / ( 1000 * 60 * 60 ) ) ) / 100

        // Emit the stats in some useful way
        log.info "House using $power Watts, consumed $consumption kWh/month for last $minutes minutes"
        log.debug state
        logEvent(["current": current, "previous": previous])
    }

    // Save the current state of the meter
    state.lastMeterReadingTimestamp = current.timestamp
    state.lastMeterReadingEnergy = current.energy
    state.lastMeterReadingPower = current.power
    state.lastMeterReadingPower1 = current.power1
    state.lastMeterReadingPower2 = current.power2
}

// Store the latest update in elasticsearch
def logEvent(readings) {
	def indexName = indexName()

	try {
        def request = new physicalgraph.device.HubAction(
            method: "POST",
            path: "/$indexName/doc",
            headers: [
                "HOST": indexHost
            ],
            body: dataEntry(readings),
            null,
            [ callback: elasticsearchResponse ]
        )
		log.debug "request {$request}"
        sendHubCommand(request)
    }
    catch (Exception e) {
    	log.error "Caught exception $e calling elasticsearch"
    }
}

// Derive the index name to store readings
String indexName() {
	def prefix = indexPrefix
    def datestamp = new Date().format("yyyy.MM.dd", TimeZone.getTimeZone('UTC'))

    return "$prefix-$datestamp"
}

// Construct the actual map of data to store in Elasticsearch
Map dataEntry(readings) {
    def elapsed = readings.current.timestamp - readings.previous.timestamp
    def probe1_ratio = asInt(readings.current.power1) / (asInt(readings.current.power1) + asInt(readings.current.power2))

	def entry = [
    	"@timestamp": new Date().format("yyyy-MM-dd'T'HH:mm:ss.SX", TimeZone.getTimeZone('UTC')),
        electricity: [
        	timestamp: new Date(state.lastMeterReadingTimestamp).format("yyyy-MM-dd'T'HH:mm:ss.SX", TimeZone.getTimeZone('UTC')),
            period_seconds: elapsed / 1000,
            total: [
                kwh: [
                    cumulative: readings.current.energy,
                    period_total: readings.current.energy - readings.previous.energy,
                    per_month: (readings.current.energy - readings.previous.energy) * 30 * 24 * 60 * 60 * 1000 / elapsed,
                ],
                watts: [
                    current: readings.current.power,
                    previous: readings.previous.power,
                    period_average: (readings.current.energy - readings.previous.energy) * 1000 / ( elapsed / ( 1000 * 60 * 60 ) ),
                ],
            ],
            probe1: [
                kwh: [
                    period_total: probe1_ratio * (readings.current.energy - readings.previous.energy),
                    per_month: probe1_ratio * (readings.current.energy - readings.previous.energy) * 30 * 24 * 60 * 60 * 1000 / elapsed,
                ],
                watts: [
                    current: readings.current.power1,
                    previous: readings.previous.power1,
                    period_average: probe1_ratio * (readings.current.energy - readings.previous.energy) * 1000 / ( elapsed / ( 1000 * 60 * 60 ) ),
                ],
            ],
            probe2: [
                kwh: [
                    period_total: (1 - probe1_ratio) * (readings.current.energy - readings.previous.energy),
                    per_month: (1 - probe1_ratio) * (readings.current.energy - readings.previous.energy) * 30 * 24 * 60 * 60 * 1000 / elapsed,
                ],
                watts: [
                    current: readings.current.power2,
                    previous: readings.previous.power2,
                    period_average: (1 - probe1_ratio) * (readings.current.energy - readings.previous.energy) * 1000 / ( elapsed / ( 1000 * 60 * 60 ) ),
                ],
            ],
        ],
        hvac: [
        	heating: [
            	on: state.heatingState,
                setpoint: theThermostat.currentValue("heatingSetpoint"),
            ],
            cooling: [
            	on: state.coolingState,
                setpoint: theThermostat.currentValue("coolingSetpoint"),
            ],
            temperature: [
            	indoor: state.indoorTemperature,
                // Outdoor temp is filled in below
            ],
        ],
    ]

    // Missing weather data is reported as 32,768 degrees. We only report the
    // temperature outside if the seas aren't boiling.
    if (state.outdoorTemperature < 212) {
    	entry.hvac.temperature.outdoor = state.outdoorTemperature
    }

    return entry
}

int asInt(value) {
	if (!value) {
    	return value
    }

    try {
    	return value as Integer
    }
    catch (Exception e) {
    	log.error "Caught exception $e converting '$value' to integer"
        return null
    }
}

void elasticsearchResponse(hubResponse) {
	log.debug "hubResponse: status {$hubResponse.status}: {$hubResponse}"
}

