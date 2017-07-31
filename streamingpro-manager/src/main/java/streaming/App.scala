package streaming

import net.csdn.ServiceFramwork
import net.csdn.bootstrap.Application
import org.apache.velocity.app.Velocity
import streaming.common.ParamsUtil
import streaming.db.ManagerConfiguration
import streaming.service.MonitorScheduler

/**
  * Created by allwefantasy on 12/7/2017.
  */
object App {
  def main(args: Array[String]): Unit = {
    ManagerConfiguration.config = new ParamsUtil(args)
    require(ManagerConfiguration.config.hasParam("yarnUrl"), "-yarnUrl is required")
    ServiceFramwork.scanService.setLoader(classOf[App])
    ServiceFramwork.registerStartWithSystemServices(classOf[MonitorScheduler])
    Application.main(Array())
  }
}

object StreamingManagerApp {
  def main(args: Array[String]): Unit = {
    App.main(args)
  }
}
