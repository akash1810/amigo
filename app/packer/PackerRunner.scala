package packer

import java.nio.charset.StandardCharsets
import java.nio.file.{ Path, Files }

import ansible.PlaybookGenerator
import event.EventBus
import models.Bake
import play.api.Logger
import play.api.libs.json.Json
import prism.Prism

import scala.concurrent.{ Future, Promise }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

object PackerRunner {

  private val packerCmd = sys.env.get("PACKER_HOME").map(ph => s"$ph/packer").getOrElse("packer")

  /**
   * Starts a Packer process to create an image using the given recipe.
   *
   * @return a Future of the process's exit value
   */
  def createImage(bake: Bake, prism: Prism, eventBus: EventBus)(implicit packerConfig: PackerConfig): Future[Int] = {
    val playbookYaml = PlaybookGenerator.generatePlaybook(bake.recipe)
    val playbookFile = Files.createTempFile(s"amigo-ansible-${bake.recipe.id.value}", ".yml")
    Files.write(playbookFile, playbookYaml.getBytes(StandardCharsets.UTF_8)) // TODO error handling

    prism.findAllAWSAccountNumbers() flatMap { awsAccountNumbers =>
      Logger.info(s"AMI will be shared with the following AWS accounts: $awsAccountNumbers")
      val packerBuildConfig = PackerBuildConfigGenerator.generatePackerBuildConfig(bake, playbookFile, awsAccountNumbers)
      val packerJson = Json.prettyPrint(Json.toJson(packerBuildConfig))
      val packerConfigFile = Files.createTempFile(s"amigo-packer-${bake.recipe.id.value}", ".json")
      Files.write(packerConfigFile, packerJson.getBytes(StandardCharsets.UTF_8)) // TODO error handling

      executePacker(bake, playbookFile, packerConfigFile, eventBus)
    }
  }

  private def executePacker(bake: Bake, playbookFile: Path, packerConfigFile: Path, eventBus: EventBus): Future[Int] = {
    val packerProcess = new ProcessBuilder()
      .command(packerCmd, "build", "-machine-readable", packerConfigFile.toAbsolutePath.toString)
      .start()

    val exitValuePromise = Promise[Int]()

    val runnable = new Runnable {
      def run(): Unit = PackerProcessMonitor.monitorProcess(packerProcess, exitValuePromise, bake.bakeId, eventBus)
    }
    val listenerThread = new Thread(runnable, s"Packer process monitor for ${bake.recipe.id.value} #${bake.buildNumber}")
    listenerThread.setDaemon(true)
    listenerThread.start()

    val exitValueFuture = exitValuePromise.future

    // Make sure to delete the tmp files after Packer completes, regardless of success or failure
    exitValueFuture.onComplete {
      case _ =>
        Try(Files.deleteIfExists(playbookFile))
        Try(Files.deleteIfExists(packerConfigFile))
    }

    exitValueFuture
  }

}
