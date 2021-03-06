package mimir.lenses;

import java.sql._;
import collection.JavaConversions._;
import scala.util._;

import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.experiment.DatabaseUtils;
import weka.experiment.InstanceQuery;
import weka.experiment.InstanceQueryAdapter;
import moa.classifiers.Classifier;
import moa.core.InstancesHeader;
import moa.streams.ArffFileStream;
import moa.streams.InstanceStream;

import mimir.Analysis; // Java code
import mimir.ctables._;
import mimir.{Database,Mimir};
import mimir.algebra._;
import mimir.util._;
import mimir.exec._;

class MissingValueLens(name: String, args: List[Expression], source: Operator) 
  extends Lens(name, args, source) 
  with InstanceQueryAdapter
{
  var orderedSourceSchema: List[(String,Type.T)] = null
  val keysToBeCleaned = args.map( Eval.evalString(_).toUpperCase )
  var models: List[SingleVarModel] = null;
  var data: Instances = null
  var model: Model = null
  var db: Database = null

  def sourceSchema() = {
    if(orderedSourceSchema == null){
      orderedSourceSchema = 
        source.schema.toList.map( _ match { case (n,t) => (n.toUpperCase,t) } )
    }
    orderedSourceSchema
  }

  def allKeys() = { sourceSchema.map(_._1) }

  def view: Operator = {
    Project(
      allKeys().
        map( (k) => {
          val v = keysToBeCleaned.indexOf(k);
          if(v >= 0){
            ProjectArg(k,
              CaseExpression(
                List(WhenThenClause(
                  mimir.algebra.IsNullExpression(Var(k), false),
                  rowVar(v)
                )),
                Var(k)
              ))
          } else {
            ProjectArg(k, Var(k))
          }
        }).toList,
      source
    )
  }
  def build(db: Database): Unit = {
    this.db = db
    val schema = source.schema.keys.map(_.toUpperCase).toList;
    val results = db.backend.execute(db.convert(source));
    models =
      sourceSchema.map(
          _ match { case (n,t) => (keysToBeCleaned.indexOf(n), t) }
        ).map( _ match { case (idx,t) => 
          if(idx < 0){ 
            new NoOpModel(t).asInstanceOf[SingleVarModel]
          } else {
            val m = new MissingValueModel(this);
            data = 
              InstanceQuery.retrieveInstances(this, results);
            data.setClassIndex(idx);
            m.init(data);

            m.asInstanceOf[SingleVarModel];
          }
        })
    model = new JointSingleVarModel(models);
  }
  
  def lensType = "MISSING_VALUE"

  ////// Weka's InstanceQueryAdapter interface
  def attributeCaseFix(colName: String) = colName;
  def getDebug() = false;
  def getSparseData() = false;
  def translateDBColumnType(t: String) = {
    t.toUpperCase() match { 
      case "STRING" => DatabaseUtils.STRING;
      case "VARCHAR" => DatabaseUtils.STRING;
      case "TEXT" => DatabaseUtils.TEXT;
      case "INT" => DatabaseUtils.LONG;
      case "DECIMAL" => DatabaseUtils.DOUBLE;
    }
  }
}

class MissingValueLensBounds(model: MissingValueModel, args: List[Expression], lowerBound: Boolean)
  extends Proc(args)
{
  def get(args: List[PrimitiveValue]): PrimitiveValue =  {
    if(lowerBound){ model.lowerBound(args) } else { model.upperBound(args) }
  }
  
  def exprType(bindings: Map[String,Type.T]) = Type.TInt
  def rebuild(c: List[Expression]) = 
    new MissingValueLensBounds(model, c, lowerBound);
}

class MissingValueModel(lens: MissingValueLens)
  extends SingleVarModel(Type.TInt) 
{
  val learner: Classifier = 
        Analysis.getLearner("moa.classifiers.bayes.NaiveBayes");
  var data: Instances = null; 
  var numCorrect = 0;
  var numSamples = 0;

  def init(data: Instances) = {
    learner.setModelContext(new InstancesHeader(data));
    learner.prepareForUse();
    data.foreach( learn(_) );
  }

  def learn(dataPoint: Instance) = {
    numSamples += 1;
    if(learner.correctlyClassifies(dataPoint)){
      numCorrect += 1;
    }
    learner.trainOnInstance(dataPoint);
  }

  def classify(rowid: PrimitiveValue): List[(Double, Int)] =
  {
    val rowValues = lens.db.query(
      CTPercolator.percolate(
        Select(
          Comparison(Cmp.Eq, Var("ROWID"), rowid),
          lens.source
        )
      )
    )
    if(!rowValues.getNext()){
      throw new SQLException("Invalid Source Data ROWID: '" +rowid+"'");
    }
    val row = new DenseInstance(lens.allKeys.length);
    (0 until lens.allKeys.length).foreach( (col) => {
      val v = rowValues(col)
      if(!v.isInstanceOf[NullPrimitive]){
        row.setValue(col, v.asDouble)
      }
    })
    row.setDataset(data)
    learner.getVotesForInstance(row).
      toList.
      zipWithIndex.
      filter( _._1 > 0 )
  }

  ////// Model implementation
  def mostLikelyValue(args: List[PrimitiveValue]): PrimitiveValue =
    { IntPrimitive(classify(args(0)).minBy(_._1)._2); }
  def lowerBound(args: List[PrimitiveValue]) =
    {  
      val classes = classify(args(0));
      IntPrimitive(classes.minBy(_._1)._2)
    }
  def upperBound(args: List[PrimitiveValue]) =
    {  
      val classes = classify(args(0));
      IntPrimitive(classes.maxBy(_._1)._2)
    }
  def lowerBoundExpr(args: List[Expression]) =
    {  
      new MissingValueLensBounds(this, args, true)
    }
  def upperBoundExpr(args: List[Expression]) =
    {  
      new MissingValueLensBounds(this, args, false)
    }
  def sample(seed: Long, args: List[PrimitiveValue]):  PrimitiveValue =
    {
      val classes = classify(args(0));
      val tot_cnt = classes.map(_._1).sum;
      val pick = new Random(seed).nextInt() % tot_cnt
      val cumulative_counts = 
        classes.scanLeft(0.0)(
            ( cumulative, cnt_class ) => cumulative + cnt_class._1 
        )
      val pick_idx: Int = cumulative_counts.indexWhere( pick < _ )
      return IntPrimitive(classes(pick_idx)._2)
    }

}
// class MissingValueAnalysis(db: Database, idx: Int, ctx: MissingValueLens) extends CTAnalysis(db) {
//   def varType: Type.T = Type.TInt
//   def isCategorical: Boolean = true
  

  
//   def computeMLE(element: List[PrimitiveValue]): PrimitiveValue = 
//   {
//     val classes = classify(element(0));
//     var maxClass = 0;
//     var maxLikelihood = classes(0);
//     (1 until classes.length).foreach( (i) => {
//       if(classes(i) > maxLikelihood){ 
//         maxLikelihood = classes(i);
//         maxClass = i
//       }
//     })
//     return new IntPrimitive(maxClass)
//   }
//   def computeEqConfidence(element: List[PrimitiveValue], value: PrimitiveValue): Double = 0.0
//   def computeBounds(element: List[PrimitiveValue]): (Double,Double) = (0.0,0.0)
//   def computeStdDev(element: List[PrimitiveValue]): Double = 0.0
  
// }