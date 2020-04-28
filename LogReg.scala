package tricount
import org.apache.log4j.LogManager
import org.apache.spark.{SparkConf, SparkContext}
import scala.collection.mutable
import scala.collection.mutable.{LinkedList}
object LogReg {
  def main(args: Array[String]) {
    val logger: org.apache.log4j.Logger = LogManager.getRootLogger
    if (args.length != 2) {
      logger.info("Usage:\nwc.WordCountMain <input dir> <output dir>")
      System.exit(1)
    }
    val conf = new SparkConf()
    val sc = new SparkContext(conf)
    val tol=0.0001
    val weights = sc.textFile("s3://mr-manav-bucket-1/weights/weights.txt").map(line=>{
      val s=line.split(",")
      (s(0).toInt,s(1).toDouble)
    }).collect
    var curr_error = Double.PositiveInfinity;
    var flag=0
    var iter = 0
    while(flag==0){
      logger.info("Epoch:"+iter)
      val weights_br = sc.broadcast(weights)
      var upd=new LinkedList[(Integer,Double)]()
      val new_error = sc.doubleAccumulator("Current Negative Log likelihood")
      val n = sc.longAccumulator("no of observations")
      val inp_rdd = sc.textFile(args(0))
      val pgd = inp_rdd.map(line => {
        val s = line.split(",")
        val wts = weights_br.value
        var sum = wts(0)._2
        for (i <- 1 to wts.size - 1) {
          sum = sum + wts(i)._2 * s(i).toDouble
        }
        val h=1/(1+Math.exp(-sum))
        val y=s(0).toDouble
        val nll=(-1)*y*Math.log(h)-(1-y)*Math.log(1-h)
        val diff = h-y
        var pgd_map=new mutable.HashMap[Integer,Double]()
        new_error.add(nll)
        n.add(1)
        for (i <- 0 to wts.size - 1) {
          if (i == 0) {
            pgd_map.put(i,diff)
          } else {
            pgd_map.put(i,diff*s(i).toDouble)
          }
        }
        pgd_map
      }).collect()
      val mean_nll=new_error.value/n.value
      logger.info("the mean nll is:"+mean_nll)
      val nob=n.value
      if(math.abs(mean_nll-curr_error)>tol && mean_nll<curr_error){
        var arr=new Array[(Integer,Double)](pgd.length*weights.size)
        var k=0
        for(i<- 0 to pgd.length-1){
          for(t<-0 to pgd(i).keySet.size-1){
            arr(k)=(t,pgd(i)(t))
            k=k+1
          }
        }
        var new_wts=sc.parallelize(arr.toSeq).reduceByKey(_+_).map(line=>{
          val wts=weights_br.value(line._1)
          (line._1,wts._2-0.2*line._2/nob)
        }).collect()
        for(i<-0 to new_wts.length-1){
          weights(new_wts(i)._1)=(new_wts(i)._1,new_wts(i)._2)
        }
        curr_error=mean_nll
      }
      else{
        val weights_rdd=sc.parallelize(weights)
       weights_rdd.saveAsTextFile(args(1))
        flag=1
      }
      iter+=1;
    }
  }
}
