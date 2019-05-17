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
    section("Thermometers") {
        input "indoorTemp", "capability.temperatureMeasurement", required: false, title: "Indoors"
        input "outdoorTemp", "capability.temperatureMeasurement", required: false, title: "Outdoors"
    }
    section("Hygrometers") {
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
    // Subscribe to thermostat state changes
    subscribe(theThermostat, "thermostatOperatingState.heating", hvacHandler)
    subscribe(theThermostat, "thermostatOperatingState.idle", hvacHandler)
    subscribe(theThermostat, "thermostatOperatingState.cooling", hvacHandler)
    subscribe(theThermostat, "thermostatMode.off", hvacHandler)

    // Subscribe to changes in the temperature setpoints
    subscribe(theThermostat, "heatingSetpoint", heatingSetpointHandler)
    subscribe(theThermostat, "coolingSetpoint", coolingSetpointHandler)

    // Subscribe to temperature and humidity changes
    subscribe(indoorTemp, "temperature", indoorTemperatureHandler)
    subscribe(outdoorTemp, "temperature", outdoorTemperatureHandler)
    subscribe(indoorHumidity, "humidity", indoorHumidityHandler)
    subscribe(outdoorHumidity, "humidity", outdoorHumidityHandler)

    // Subscribe to energy meter to detect updates
    subscribe(theMeter, "energy", energyHandler)
    subscribe(theMeter, "power", powerHandlerTotal)
    subscribe(theMeter, "power1", powerHandlerProbe1)
    subscribe(theMeter, "power2", powerHandlerProbe2)

    // Set off the logging loop
    loggingLoop()
}

// Refresh the energy meter. When it finishes, the subscribed attributes will cause
// the new reading to be logged.
def loggingLoop() {
    logCurrentState()
    runIn(60 * interval, loggingLoop)
}

// Store the latest data in elasticsearch
def logCurrentState() {
    def indexName = indexName()
    state.report['@timestamp'] = new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('UTC'))

    try {
        def request = new physicalgraph.device.HubAction(
            method: "POST",
            path: "/$indexName/doc",
            headers: [
                "HOST": indexHost
            ],
            body: state.report,
            null,
            [ callback: elasticsearchResponse ]
        )
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

// Handler for changes to heating/cooling state
def hvacHandler(evt) {
    log.info evt.descriptionText

    def thermostat = evt.device
    def newState = evt.stringValue
    def timestamp = evt.date.format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('UTC'))

    // Update the report object with the new furnace state
    state.report.hvac.heating.on = (newState == "heating") ? true : false
    state.report.hvac.cooling.on = (newState == "cooling") ? true : false
    state.report.hvac.timestamp = timestamp
}

def heatingSetpointHandler(evt) {
	log.info evt.descriptionText
	state.report.hvac.heating.setpoint = evt.value
}

def coolingSetpointHandler(evt) {
	log.info evt.descriptionText
	state.report.hvac.cooling.setpoint = evt.value
}

// Generic handler for changes in temperature and humidity
def climateHandler(evt, where) {
    log.info evt.descriptionText
    where.value = evt.value
    where.timestamp = evt.date.format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('UTC'))
}

// Stupid wrappers for the generic climate handler
def indoorTemperatureHandler(evt) { climateHandler(evt, state.report.climate.indoor.temperature) }
def indoorHumidityHandler(evt) { climateHandler(evt, state.report.climate.indoor.humidity) }
def outdoorTemperatureHandler(evt) { climateHandler(evt, state.report.climate.outdoor.temperature) }
def outdoorHumidityHandler(evt) { climateHandler(evt, state.report.climate.outdoor.humidity) }

// Update the report when there's a new energy reading
def energyHandler(evt) {
    log.info evt.descriptionText

    def currentEnergy = evt.value
    def previousEnergy = state.report.electricity.total.kwh.cumulative
    def previousTimestamp = parseTimestamp(state.report.electricity.total.kwh.timestamp)
    def elapsed = evt.date - previousTimestamp

    state.report.electricity.total.kwh = [
        timestamp: evt.date.format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('UTC')),
        cumulative: currentEnergy,
        period_total: currentEnergy - previousEnergy,
        per_month: (currentEnergy - previousEnergy) * 30 * 24 * 60 * 60 * 1000 / elapsed,
    ]
}

// Generic handler for new power readings
def powerHandler(evt, where) {
    log.info evt.descriptionText

    def timestamp = evt.date.format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('UTC'))
    def currentPower = asInt(evt.value)
    def previousPower = asInt(where.watts.current)
    def previousTimestamp = parseTimestamp(where.watts.timestamp)
    def elapsed = evt.date - previousTimestamp

    where.watts = [
        timestamp: timestamp,
        current: currentPower,
        previous: previousPower,
        period_average: (currentPower + previousPower) / 2,
    ]

    // We don't get a cumulative number for the probes, and we shouldn't
    // try to estimate it, because the errors will propagate.
    if (!where.kwh.cumulative) {
        def kW = (currentPower + previousPower) / (2 * 1000)
        def h  = elapsed / (1000 * 60 * 60)

        where.kwh = [
            timestamp: timestamp,
            period_total: kW * h,
            per_month: kW * h * 24 * 30,
        ]
    }
}

// Stupid wrappers for the generic handler
def powerHandlerTotal(evt) { powerHandler(evt, state.report.electricity.total) }
def powerHandlerProbe1(evt) { powerHandler(evt, state.report.electricity.probe1) }
def powerHandlerProbe2(evt) { powerHandler(evt, state.report.electricity.probe2) }

// Construct the actual map of data to store in Elasticsearch
Map dataEntry(readings) {
    def elapsed = readings.current.timestamp - readings.previous.timestamp
    def probe1_ratio = asInt(readings.current.power1) / (asInt(readings.current.power1) + asInt(readings.current.power2))
    def timestamp = new Date(state.lastMeterReadingTimestamp).format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('UTC'))

    def entry = [
        "@timestamp": new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSX", TimeZone.getTimeZone('UTC')),
        electricity: [
            timestamp: timestamp,
            period_seconds: elapsed / 1000,
            total: [
                kwh: [
                    timestamp: timestamp,
                    cumulative: readings.current.energy,
                    period_total: readings.current.energy - readings.previous.energy,
                    per_month: (readings.current.energy - readings.previous.energy) * 30 * 24 * 60 * 60 * 1000 / elapsed,
                ],
                watts: [
                    timestamp: timestamp,
                    current: readings.current.power,
                    previous: readings.previous.power,
                    period_average: (readings.current.energy - readings.previous.energy) * 1000 / ( elapsed / ( 1000 * 60 * 60 ) ),
                ],
            ],
            probe1: [
                kwh: [
                    timestamp: timestamp,
                    period_total: probe1_ratio * (readings.current.energy - readings.previous.energy),
                    per_month: probe1_ratio * (readings.current.energy - readings.previous.energy) * 30 * 24 * 60 * 60 * 1000 / elapsed,
                ],
                watts: [
                    timestamp: timestamp,
                    current: readings.current.power1,
                    previous: readings.previous.power1,
                    period_average: probe1_ratio * (readings.current.energy - readings.previous.energy) * 1000 / ( elapsed / ( 1000 * 60 * 60 ) ),
                ],
            ],
            probe2: [
                kwh: [
                    timestamp: timestamp,
                    period_total: (1 - probe1_ratio) * (readings.current.energy - readings.previous.energy),
                    per_month: (1 - probe1_ratio) * (readings.current.energy - readings.previous.energy) * 30 * 24 * 60 * 60 * 1000 / elapsed,
                ],
                watts: [
                    timestamp: timestamp,
                    current: readings.current.power2,
                    previous: readings.previous.power2,
                    period_average: (1 - probe1_ratio) * (readings.current.energy - readings.previous.energy) * 1000 / ( elapsed / ( 1000 * 60 * 60 ) ),
                ],
            ],
        ],
        hvac: [
            timestamp: timestamp,
            heating: [
                on: state.heatingState,
                setpoint: theThermostat.currentValue("heatingSetpoint"),
            ],
            cooling: [
                on: state.coolingState,
                setpoint: theThermostat.currentValue("coolingSetpoint"),
            ]
        ],
        climate: [
            indoor: [
                temperature: [
                    value: state.indoorTemperature,
                    timestamp: timestamp,
                ],
                humidity: [
                    value: state.indoorHumidity,
                    timestamp: timestamp,
                ]
            ],
            outdoor: [
                temperature: [
                    value: state.outdoorTemperature,
                    timestamp: timestamp,
                ],
                humidity: [
                    value: state.outdoorHumidity,
                    timestamp: timestamp,
                ]
            ],
        ]
    ]

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

Date parseTimestamp(str) {
    def format = "yyyy-MM-dd'T'HH:mm:ss"

    try { return Date.parse(format + "X", str) } catch (Exception e) {}
    try { return Date.parse(format + ".SX", str) } catch (Exception e) {}
    try { return Date.parse(format + ".SSX", str) } catch (Exception e) {}
    try { return Date.parse(format + ".SSSX", str) }
    catch (Exception e) {
        throw e
    }
}

