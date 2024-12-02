package org.apache.storm.scheduler.resource.strategies.scheduling;

import org.apache.storm.scheduler.*;
import org.apache.storm.scheduler.resource.RasNode;
import org.apache.storm.scheduler.resource.strategies.scheduling.GenericResourceAwareStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class EnergyAwareStrategy extends GenericResourceAwareStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(EnergyAwareStrategy.class);
    String PROFILES = "powerstorm.scheduler.profiles";
    String ENERGY_AWARENESS = "powerstorm.scheduler.energyawareness";

    HashMap<String, Double> energyScores = new HashMap<>();
    HashMap<String, Double> processingScores = new HashMap<>();
    Double energyAwareness = 1.0;

    public EnergyAwareStrategy(){
        super();
    }

    protected void fillProfile(TopologyDetails topologyDetails){
        fillEnergyAwareness(topologyDetails);
        fillScores(topologyDetails);
    }

    private void fillScores(TopologyDetails topologyDetails) {
        List<String> profiles = (List<String>) topologyDetails.getConf().getOrDefault(this.PROFILES, new ArrayList<>());
        Integer maxProcessing = 0;
        Double maxEnergyPower = 0.0;
        for (String profile: profiles){
            List<String> splitedProfile = Arrays.asList(profile.split(","));
            Integer cores = Integer.parseInt(splitedProfile.get(1));
            Integer clock = Integer.parseInt(splitedProfile.get(2));
            Integer idlePower = Integer.parseInt(splitedProfile.get(3));
            Integer peakPower = Integer.parseInt(splitedProfile.get(4));
            Integer processingPower = cores*clock;
            if (maxProcessing<processingPower){
                maxProcessing = processingPower;
            }
            //Double energy = 1.0/((peakPower-idlePower)/processingPower);
            Double energy = 1.0/(peakPower-idlePower);
            if (maxEnergyPower<energy){
                maxEnergyPower = energy;
            }
        }

        for (String profile: profiles){
            List<String> splitedProfile = Arrays.asList(profile.split(","));
            String hostname = splitedProfile.get(0);
            Integer cores = Integer.parseInt(splitedProfile.get(1));
            Integer clock = Integer.parseInt(splitedProfile.get(2));
            Integer idlePower = Integer.parseInt(splitedProfile.get(3));
            Integer peakPower = Integer.parseInt(splitedProfile.get(4));
            Double processingPower = 1.0*cores*clock;
            processingScores.put(hostname, processingPower/maxProcessing);
            //Double energy = 1.0/((peakPower-idlePower)/processingPower);
            Double energy = 1.0/(peakPower-idlePower);
            energyScores.put(hostname, energy/maxEnergyPower);
        }
    }

    private void fillEnergyAwareness(TopologyDetails topologyDetails) {
        try{
            energyAwareness = Double.parseDouble((String) topologyDetails.getConf().getOrDefault(this.ENERGY_AWARENESS, "0.0"));
        }catch (NullPointerException ex){
            energyAwareness = 1.0;
        }
        if (energyAwareness > 1.0){
            energyAwareness = 1.0;
        } else if (energyAwareness < 0.0){
            energyAwareness = 0.0;
        }
    }

    protected TreeSet<ObjectResources> sortObjectResources(AllResources allResources, ExecutorDetails exec, TopologyDetails topologyDetails, ExistingScheduleFunc existingScheduleFunc) {
        TreeSet<ObjectResources> res = GenericResourceAwareStrategy.sortObjectResourcesImpl(allResources, exec, topologyDetails, existingScheduleFunc);
        fillProfile(topologyDetails);
        EnergyAwareStrategy passby = this;

        TreeSet<ObjectResources> sortedObjectResources = new TreeSet((Object oo1, Object oo2) -> {
            ObjectResources o1 = (ObjectResources) oo1;
            ObjectResources o2 = (ObjectResources) oo2;

            int execsScheduled1 = existingScheduleFunc.getNumExistingSchedule(o1.id);
            int execsScheduled2 = existingScheduleFunc.getNumExistingSchedule(o2.id);
            Double score1 = 0.0;
            Double score2 = 0.0;
            RasNode node1 = passby.idToNode(o1.id);
            RasNode node2 = passby.idToNode(o2.id);

            if (node1 != null && node2 != null){
                Double energyScore1 = energyScores.getOrDefault(node1.getHostname(), 0.0);
                Double energyScore2 = energyScores.getOrDefault(node2.getHostname(), 0.0);
                Double processingScore1 = processingScores.getOrDefault(node1.getHostname(), 0.0);
                Double processingScore2 = processingScores.getOrDefault(node2.getHostname(), 0.0);
                score1 = passby.energyAwareness*energyScore1+(1-passby.energyAwareness)*processingScore1;
                score2 = passby.energyAwareness*energyScore2+(1-passby.energyAwareness)*processingScore2;
            }

            if (score1 > score2){
                return -1;
            } else if (score1 < score2) {
                return 1;
            } else if (execsScheduled1 > execsScheduled2) {
                return -1;
            } else if (execsScheduled1 < execsScheduled2) {
                return 1;
            } else if (o1.effectiveResources > o2.effectiveResources) {
                return -1;
            } else if (o1.effectiveResources < o2.effectiveResources) {
                return 1;
            } else {
                return 1;
            }
        });
        sortedObjectResources.addAll(res);
        LOG.info("POWERSTORM: " + sortedObjectResources.toString());
        return sortedObjectResources;
    }


}
