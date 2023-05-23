package org.matsim.RunFreight;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freight.carrier.Carrier;
import org.matsim.contrib.freight.carrier.Tour;
import org.matsim.contrib.freight.controler.CarrierScoringFunctionFactory;
import org.matsim.contrib.freight.events.FreightTourEndEvent;
import org.matsim.contrib.freight.events.FreightTourStartEvent;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.*;

/**
 * @author Kai Martins-Turner (kturner)
 */
class MyEventBasedCarrierScorer implements CarrierScoringFunctionFactory {

	@Inject
	private Network network;

	@Inject
	private Scenario scenario;


	public ScoringFunction createScoringFunction(Carrier carrier) {
		SumScoringFunction sf = new SumScoringFunction();
		sf.addScoringFunction(new EventBasedScoring());
		return sf;
	}


	/**
	 * Calculate the carrier's score based on Events.
	 * Currently, it includes:
	 * - fixed costs (using FreightTourEndEvent)
	 * - time-dependent costs (using FreightTourStart- and -EndEvent)
	 * - distance-dependent costs (using LinkEnterEvent)
	 */
	private class EventBasedScoring implements SumScoringFunction.ArbitraryEventScoring {

		final Logger log = LogManager.getLogger(EventBasedScoring.class);
		private double score;

		private final Map<Id<Tour>, Double> tourStartTime = new LinkedHashMap<>();

		public EventBasedScoring() {
			super();
		}

		@Override public void finish() {
		}

		@Override public double getScore() {
			return score;
		}

		@Override public void handleEvent(Event event) {
			log.warn(event.toString());
			if (event instanceof FreightTourStartEvent freightTourStartEvent) {
				handleEvent(freightTourStartEvent);
			} else if (event instanceof FreightTourEndEvent freightTourEndEvent) {
				handleEvent(freightTourEndEvent);
			} else if (event instanceof LinkEnterEvent linkEnterEvent) {
				handleEvent(linkEnterEvent);
			}
		}

		private void handleEvent(FreightTourStartEvent event) {
			// Save time of freight tour start
			tourStartTime.put(event.getTourId(), event.getTime());
		}

		//Fix costs for vehicle usage
		private void handleEvent(FreightTourEndEvent event) {
			//Fix costs for vehicle usage
			final VehicleType vehicleType = (VehicleUtils.findVehicle(event.getVehicleId(), scenario)).getType();
			score = score - vehicleType.getCostInformation().getFixedCosts();


			// variable costs per time
			double tourDuration = event.getTime() - tourStartTime.get(event.getTourId());
			score = score - (tourDuration * vehicleType.getCostInformation().getCostsPerSecond());
		}

		private void handleEvent(LinkEnterEvent event) {
			final double distance = network.getLinks().get(event.getLinkId()).getLength();
			final double costPerMeter = (VehicleUtils.findVehicle(event.getVehicleId(), scenario)).getType().getCostInformation().getCostsPerMeter();
			// variable costs per distance
			score = score - (distance * costPerMeter);
		}

	}

}
