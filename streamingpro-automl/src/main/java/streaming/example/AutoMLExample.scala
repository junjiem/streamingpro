package streaming.example


/**
  * Created by allwefantasy on 12/9/2018.
  */

import com.salesforce.op._
import com.salesforce.op.evaluators.Evaluators
import com.salesforce.op.features.{FeatureSparkTypes, _}
import com.salesforce.op.features.types._
import com.salesforce.op.readers.DataFrameFieldNames._
import com.salesforce.op.readers.DataReaders
import com.salesforce.op.stages.impl.classification.BinaryClassificationModelSelector
import com.salesforce.op.stages.impl.classification.ClassificationModelsToTry._
import org.apache.spark.SparkConf
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.types.{StringType, StructField, StructType}

/**
  * Define a case class corresponding to our data file (nullable columns must be Option types)
  *
  * @param id       passenger id
  * @param survived 1: survived, 0: did not survive
  * @param pClass   passenger class
  * @param name     passenger name
  * @param sex      passenger sex (male/female)
  * @param age      passenger age (one person has a non-integer age so this must be a double)
  * @param sibSp    number of siblings/spouses traveling with this passenger
  * @param parCh    number of parents/children traveling with this passenger
  * @param ticket   ticket id string
  * @param fare     ticket price
  * @param cabin    cabin id string
  * @param embarked location where passenger embarked
  */
case class Passenger
(
  id: Int,
  survived: Int,
  pClass: Option[Int],
  name: Option[String],
  sex: Option[String],
  age: Option[Double],
  sibSp: Option[Int],
  parCh: Option[Int],
  ticket: Option[String],
  fare: Option[Double],
  cabin: Option[String],
  embarked: Option[String]
)

/**
  * A simplified TransmogrifAI example classification app using the Titanic dataset
  */
object OpTitanicSimple {

  /**
    * Run this from the command line with
    * ./gradlew sparkSubmit -Dmain=com.salesforce.hw.OpTitanicSimple -Dargs=/full/path/to/csv/file
    */
  def main(args: Array[String]): Unit = {

    val csvFilePath = "/Users/allwefantasy/Downloads/train.csv"
    println(s"Using user-supplied CSV file path: $csvFilePath")

    // Set up a SparkSession as normal
    val conf = new SparkConf().setAppName(this.getClass.getSimpleName.stripSuffix("$")).setMaster("local[*]")
    implicit val spark = SparkSession.builder.config(conf).getOrCreate()

    ////////////////////////////////////////////////////////////////////////////////
    // RAW FEATURE DEFINITIONS
    /////////////////////////////////////////////////////////////////////////////////


    // Define features using the OP types based on the data
    val survived = FeatureBuilder.RealNN[Passenger].extract(_.survived.toRealNN).asResponse
    val pClass = FeatureBuilder.PickList[Passenger].extract(_.pClass.map(_.toString).toPickList).asPredictor
    val name = FeatureBuilder.Text[Passenger].extract(_.name.toText).asPredictor
    val sex = FeatureBuilder.PickList[Passenger].extract(_.sex.map(_.toString).toPickList).asPredictor
    val age = FeatureBuilder.Real[Passenger].extract(_.age.toReal).asPredictor
    val sibSp = FeatureBuilder.Integral[Passenger].extract(_.sibSp.toIntegral).asPredictor
    val parCh = FeatureBuilder.Integral[Passenger].extract(_.parCh.toIntegral).asPredictor
    val ticket = FeatureBuilder.PickList[Passenger].extract(_.ticket.map(_.toString).toPickList).asPredictor
    val fare = FeatureBuilder.Real[Passenger].extract(_.fare.toReal).asPredictor
    val cabin = FeatureBuilder.PickList[Passenger].extract(_.cabin.map(_.toString).toPickList).asPredictor
    val embarked = FeatureBuilder.PickList[Passenger].extract(_.embarked.map(_.toString).toPickList).asPredictor

    ////////////////////////////////////////////////////////////////////////////////
    // TRANSFORMED FEATURES
    /////////////////////////////////////////////////////////////////////////////////

    // Do some basic feature engineering using knowledge of the underlying dataset
    val familySize = sibSp + parCh + 1
    val estimatedCostOfTickets = familySize * fare
    val pivotedSex = sex.pivot()
    val normedAge = age.fillMissingWithMean().zNormalize()
    val ageGroup = age.map[PickList](_.value.map(v => if (v > 18) "adult" else "child").toPickList)

    // Define a feature of type vector containing all the predictors you'd like to use
    val rawFeatures = Seq(
      pClass, name, age, sibSp, parCh, ticket,
      cabin, embarked, familySize, estimatedCostOfTickets,
      pivotedSex, ageGroup
    )
    val passengerFeatures = rawFeatures.transmogrify()

    // Optionally check the features with a sanity checker
    val sanityCheck = true
    val finalFeatures = if (sanityCheck) survived.sanityCheck(passengerFeatures) else passengerFeatures


    //    def getSchema(rawFeatures: Array[OPFeature]): StructType = {
    //      val keyField = StructField(name = KeyFieldName, dataType = StringType, nullable = false)
    //      val featureFields = rawFeatures.map(FeatureSparkTypes.toStructField(_))
    //      StructType(keyField +: featureFields)
    //    }
    //
    //    val ds = spark.read.csv(csvFilePath)
    //    val schema = getSchema(rawFeatures)
    //    implicit val rowEnc = RowEncoder(schema)
    //    ds.flatMap(record => generateRow(key(record), record, rawFeatures))


    //    // Define the model we want to use (here a simple logistic regression) and get the resulting output
    //    val (prediction, rawPrediction, prob) =
    //    BinaryClassificationModelSelector.withTrainValidationSplit()
    //      .setModelsToTry(LogisticRegression)
    //      .setInput(survived, finalFeatures).getOutput()
    //
    //    val evaluator = Evaluators.BinaryClassification()
    //      .setLabelCol(survived)
    //      .setRawPredictionCol(rawPrediction)
    //      .setPredictionCol(prediction)
    //      .setProbabilityCol(prob)

    ////////////////////////////////////////////////////////////////////////////////
    // WORKFLOW
    /////////////////////////////////////////////////////////////////////////////////

    import spark.implicits._
    // Needed for Encoders for the Passenger case class
    // Define a way to read data into our Passenger class from our CSV file
    val trainDataReader = DataReaders.Simple.csvCase[Passenger](
      path = Option(csvFilePath),
      key = _.id.toString
    )

    // Define a new workflow and attach our data reader
    val workflow =
    new WowOpWorkflow()
      .setResultFeatures(survived, finalFeatures)
      .setReader(trainDataReader)

    // Fit the workflow to the data
    val fittedWorkflow = workflow.trainFeatureModel()
    fittedWorkflow.save("/tmp/model1", overwrite = true)
    //val df = fittedWorkflow.computeDataUpTo(finalFeatures)


    //    // Manifest the result features of the workflow
    //    println("Scoring the model")
    //    val (dataframe, metrics) = fittedWorkflow.scoreAndEvaluate(evaluator = evaluator)
    //
    //    println("Transformed dataframe columns:")
    //    dataframe.columns.foreach(println)
    //    println("Metrics:")
    //    println(metrics)
  }
}


