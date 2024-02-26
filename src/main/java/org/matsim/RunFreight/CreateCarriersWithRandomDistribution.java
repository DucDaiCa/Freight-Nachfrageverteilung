package org.matsim.RunFreight;

import com.graphhopper.jsprit.core.problem.job.Shipment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freight.carrier.Carrier;
import org.matsim.contrib.freight.carrier.CarrierCapabilities.FleetSize;
import org.matsim.contrib.freight.carrier.CarrierPlanWriter;
import org.matsim.contrib.freight.carrier.CarrierShipment;
import org.matsim.contrib.freight.carrier.CarrierUtils;
import org.matsim.contrib.freight.carrier.CarrierVehicle;
import org.matsim.contrib.freight.carrier.Carriers;
import org.matsim.contrib.freight.carrier.TimeWindow;
import org.matsim.contrib.freight.controler.FreightUtils;
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
    final int randomSeed = 4711; //Damit bei mehrfacher Ausführung jeweils die gleichen Daten gezogen werden.
    createCarrierWithRandomDistribution(nuOfJobsToCreate, nuOfDestinations, randomSeed);
  }

  private static void createCarrierWithRandomDistribution(int nuOfJobsToCreate, int nuOfDestinations, int randomSeed) {
    Random random = MatsimRandom.getRandom();
    random.setSeed(randomSeed);

    Carriers carriers = new Carriers();
    Carrier carrier = CarrierUtils.createCarrier(Id.create("myCarrier", Carrier.class)); //@Duc: Von mir aus kannst du den auch ander benennen. Kai feb'24

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
    new CarrierPlanWriter(carriers).write("input/RandomCarriers_"+nuOfDestinations+"_RS"+randomSeed+".xml");
  }


  /**
   *
   *  method for distributing Shipments randomly to given locations
   *
   * @deprecated Ist die Variante von Duc. Bitte nicht mehr verwenden. KMT Feb'24
   * @param args String array with arguments
   * @param scenario
   */
  @Deprecated
  static void randomShipmentDistribution_fromDuc(Scenario scenario, String[] args) {
    int numShipments = 0;
    Random random = new Random();

    // um Orte und neue Anzahl von Sendungen zu speichern
    Map<String, Integer> destinationShipments = new HashMap<>();

    for (Carrier carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
      numShipments = RunFreightExample.numOfShipments(carrier, numShipments);

      for (CarrierShipment carrierShipment : carrier.getShipments().values()) {

        if (!destinationShipments.containsKey(carrierShipment.getTo().toString()))
          ;
        {
          destinationShipments.put(carrierShipment.getTo().toString(), 0);
        }
      }

      numShipments = numShipments - destinationShipments.size();
      int numOfDestination = destinationShipments.size() - 1;

      // random distribution on the HashMap entries
      for (Map.Entry<String, Integer> entry : destinationShipments.entrySet()) {

        // Zufällige Zahl von 0 bis total
        int randomValue = random.nextInt(numShipments + 1);

        // Schleife um zb. bei 4 Orten, 3 den zufälligen Anzahl von Sendungen zuzuteilen  und der
        // 4te dann den Rest oder 1 Sendung)
        if (numOfDestination != 0) {

          destinationShipments.put(entry.getKey(), randomValue + 1);

          numShipments -= randomValue;
          // log.info("Gebe mir neuen demand aus: "+numShipments);
          numOfDestination -= 1;
          // log.info("Gebe mir neuen destinationlänge aus: "+numOfDestination);

        } else {

          destinationShipments.put(entry.getKey(), numShipments + 1);
        }
      }
    }
    // log.info("Gebe mir die HashMap der Zielorte aus: "+destinationShipments);
    for (var carrier : FreightUtils.getCarriers(scenario).getCarriers().values()) {
      for (var carrierShipment : carrier.getShipments().values()) {
        for (Map.Entry<String, Integer> entry : destinationShipments.entrySet()) {
          if (entry.getKey().equals(carrierShipment.getTo().toString())) {

            CarrierShipment newCarrierShipment =
                CarrierShipment.Builder.newInstance(
                        Id.create(carrierShipment.getId(), CarrierShipment.class),
                        carrierShipment.getFrom(),
                        carrierShipment.getTo(),
                        entry.getValue())
                    .setDeliveryServiceTime((double) entry.getValue() * 180)
                    .setDeliveryTimeWindow(carrierShipment.getDeliveryTimeWindow())
                    .setPickupTimeWindow(carrierShipment.getPickupTimeWindow())
                    .setPickupServiceTime((double) entry.getValue() * 180)
                    .build();
            CarrierUtils.addShipment(carrier, newCarrierShipment);
          }
        }
      }
    }
    new CarrierPlanWriter(FreightUtils.getCarriers(scenario)).write(args[0]);
  }
}
