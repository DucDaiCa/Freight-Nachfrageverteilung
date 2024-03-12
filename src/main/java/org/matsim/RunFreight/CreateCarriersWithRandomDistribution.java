package org.matsim.RunFreight;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Random;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freight.carrier.Carrier;
import org.matsim.contrib.freight.carrier.CarrierCapabilities.FleetSize;
import org.matsim.contrib.freight.carrier.CarrierPlanWriter;
import org.matsim.contrib.freight.carrier.CarrierShipment;
import org.matsim.contrib.freight.carrier.CarrierUtils;
import org.matsim.contrib.freight.carrier.CarrierVehicle;
import org.matsim.contrib.freight.carrier.Carriers;
import org.matsim.contrib.freight.carrier.TimeWindow;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

/**
 * @author Kai Martins-Turner (kturner)
 */
public class CreateCarriersWithRandomDistribution {

  public static void main(String[] args){
    final int nuOfDestinations = 4; // 4 oder 5 nach deine Wahl
    final int nuOfJobsToCreate = nuOfDestinations * 24;
    final int randomSeed = 25; //Damit bei mehrfacher Ausführung jeweils die gleichen Daten gezogen werden.
    createCarrierWithRandomDistribution(nuOfJobsToCreate, nuOfDestinations, randomSeed);
  }

  private static void createCarrierWithRandomDistribution(int nuOfJobsToCreate, int nuOfDestinations, int randomSeed) {
    Random random = MatsimRandom.getRandom();
    random.setSeed(randomSeed);

    final String carrierName = "RandomCarrier_" + nuOfDestinations + "_RS" + randomSeed;

    Carriers carriers = new Carriers();
    Carrier carrier = CarrierUtils.createCarrier(Id.create(carrierName, Carrier.class)); //@Duc: Von mir aus kannst du den auch ander benennen. Kai feb'24

    LinkedList<String> destinationList = new LinkedList<>(Arrays.asList("108280", "104051", "15863", "143950", "143810")); //""143810" ist nur im 5-locations-Scenario enthalten

    //Create carrier Shipment
    for(int i = 0; i < nuOfJobsToCreate; i++) {
      int locationNumber = random.nextInt(nuOfDestinations);
      final String shipmentIdString = "shipment" + (i + 1);
      CarrierShipment carrierShipment = CarrierShipment.Builder
          .newInstance(Id.create(shipmentIdString, CarrierShipment.class)
              , Id.createLinkId(59933)
              , Id.createLinkId(destinationList.get(locationNumber))
              , 1
          )
          .setPickupServiceTime(3*60.)
          .setDeliveryServiceTime(3*60.)
          .setPickupTimeWindow(TimeWindow.newInstance(9*3600., 19*3600.))
          .setDeliveryTimeWindow(TimeWindow.newInstance(9*3600.,19*3600.))
          .build();
      CarrierUtils.addShipment(carrier, carrierShipment);
    }

    //add vehicle to Carrier 	<vehicle id="heavy26t_59933" depotLinkId="59933" typeId="heavy26t" earliestStart="09:00:00" latestEnd="19:00:00"/> INFINTE No jsprit 1
    CarrierVehicle carrierVehicle = CarrierVehicle.Builder.newInstance(
            Id.createVehicleId("heavy26t_59933")
            , Id.createLinkId("59933")
            , VehicleUtils.createVehicleType(Id.create("heavy26t", VehicleType.class))
        )
        .setEarliestStart(9*3600.)
        .setLatestEnd(19*3600.)
        .build();
    CarrierUtils.addCarrierVehicle(carrier, carrierVehicle);

    //more general settings:
    carrier.getCarrierCapabilities().setFleetSize(FleetSize.INFINITE);
    CarrierUtils.setJspritIterations(carrier, 1);

    carriers.addCarrier(carrier);

    new CarrierPlanWriter(carriers).write("input/" + carrierName + ".xml");
  }
}
