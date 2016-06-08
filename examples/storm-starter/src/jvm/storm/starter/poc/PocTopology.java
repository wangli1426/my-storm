package storm.starter.poc;

import backtype.storm.Config;
import backtype.storm.StormSubmitter;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;

/**
 * Created by robert on 25/5/16.
 */
public class PocTopology {
    public static String SEC_CODE = "sec_code";
    public static String ACCT_ID = "acct_id";
    public static String ORDER_NO = "order_no";
    public static String PRICE = "price";
    public static String VOLUME = "volume";
    public static String TIME = "time";

    public static String BUYER_STREAM = "buyer_stream";
    public static String SELLER_STREAM = "seller_stream";


    public static String Spout = "spout";
    public static String bolt = "bolt";

    public static void main(String[] args) {

        TopologyBuilder builder = new TopologyBuilder();

        builder.setSpout(Spout, new Spout(args[1]), 1);
        builder.setBolt(bolt, new ComputationIntensiveTransactionBolt(), 1).fieldsGrouping(Spout, BUYER_STREAM, new Fields(SEC_CODE)).fieldsGrouping(Spout, SELLER_STREAM, new Fields(SEC_CODE));

        Config config = new Config();

        try {
            StormSubmitter.submitTopologyWithProgressBar(args[0], config, builder.createTopology());
        } catch (Exception e) {
            e.printStackTrace();
        }






    }
}