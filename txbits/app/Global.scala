// Copyright (c) 2014 Viktor Stanchev & Kirk Zathey.
// This file is licensed under the Affero General Public License version 3 or later,
// see the accompanying file COPYING or http://www.gnu.org/licenses/agpl.html.

import com.googlecode.jsonrpc4j.JsonRpcHttpClient
import controllers.IAPI.CryptoAddress
import java.net.{ PasswordAuthentication, URL }
import java.net.{ PasswordAuthentication, Authenticator, URL }
import play.api.db.DB
import play.api.mvc.Result
import play.api.Play.current
import play.filters.csrf.CSRFFilter
import play.filters.headers.SecurityHeadersFilter
import scala.concurrent.duration._
import models._
import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.json.Json
import play.libs.Akka
import scala.collection.JavaConverters._
import scala.concurrent.Future
import service.sql.misc
import service.txbitsUserService
import usertrust.{ UserTrustModel, UserTrustService }
import wallet.{ WalletModel, Wallet }

package object globals {
  val masterDB = "default"
  val masterDBWallet = "wallet"
  val masterDBTrusted = "trust"

  if (Play.current.configuration.getBoolean("meta.devdb").getOrElse(false)) {
    DB.withConnection(globals.masterDB)({ implicit c =>
      misc.devdb.execute()
    })
  }

  val userModel = new UserModel(masterDB)
  val metaModel = new MetaModel(masterDB)
  val engineModel = new EngineModel(masterDB)
  val logModel = new LogModel(masterDB)

  val walletModel = new WalletModel(masterDBWallet)

  val userTrustModel = new UserTrustModel(masterDBTrusted)

  // create UserTrust actor
  val userTrustActor = current.configuration.getBoolean("usertrustservice.enabled").getOrElse(false) match {
    case true => Some(Akka.system.actorOf(UserTrustService.props(userTrustModel)))
    case false => None
  }

  // set up rpc authenticator for wallets
  val rpcAuth = DefaultAuthenticator.getInstance()

  // create wallet actors from config
  //TODO: separate wallet from frontend
  val currencies = List(
    "bitcoin" -> Wallet.CryptoCurrency.BTC,
    "litecoin" -> Wallet.CryptoCurrency.LTC,
    "peercoin" -> Wallet.CryptoCurrency.PPC,
    "primecoin" -> Wallet.CryptoCurrency.XPM)

  val enabledCurrencies = currencies.filter(c =>
    Play.current.configuration.getBoolean("wallet.%s.enabled".format(c._1)).getOrElse(false))

  val wallets = for {
    (currencyName, currency) <- enabledCurrencies
    nodeId <- Play.current.configuration.getIntList("wallet.%s.node.ids".format(currencyName)).get.asScala
  } yield {
    val result = for {
      rpcUrlString <- Play.current.configuration.getString("wallet.%s.node.%s.rpc.url".format(currencyName, nodeId))
      rpcUser <- Play.current.configuration.getString("wallet.%s.node.%s.rpc.user".format(currencyName, nodeId))
      rpcPassword <- Play.current.configuration.getString("wallet.%s.node.%s.rpc.password".format(currencyName, nodeId))
      checkDelay <- Play.current.configuration.getInt("wallet.%s.node.%s.checkDelay".format(currencyName, nodeId))
      checkInterval <- Play.current.configuration.getInt("wallet.%s.node.%s.checkInterval".format(currencyName, nodeId))
      addressDelay <- Play.current.configuration.getInt("wallet.%s.node.%s.addressDelay".format(currencyName, nodeId))
      addressInterval <- Play.current.configuration.getInt("wallet.%s.node.%s.addressInterval".format(currencyName, nodeId))
      addressPool <- Play.current.configuration.getInt("wallet.%s.node.%s.addressPool".format(currencyName, nodeId))
    } yield {
      val backupPath = Play.current.configuration.getString("wallet.%s.node.%s.backupPath".format(currencyName, nodeId)) match {
        case Some(path) if path.startsWith("/") => Some(path)
        case Some(_) =>
          Logger.warn("Backup path specified, but is not absolute (starting with /). Backups are disabled."); None
        case None => None
      }
      val coldAddress = Play.current.configuration.getString("wallet.%s.node.%s.coldAddress".format(currencyName, nodeId)) match {
        case Some(address) if CryptoAddress.isValid(address, currency.toString, Play.current.configuration.getBoolean("fakeexchange").get) => Some(address)
        case Some(_) =>
          Logger.warn("Invalid cold storage address for %s wallet. Cold storage disabled.".format(currency)); None
        case None => None
      }
      val refillEmail = Play.current.configuration.getString("wallet.%s.node.%s.refillEmail".format(currencyName, nodeId)) match {
        case Some(email) if email.contains("@") => Some(email)
        case Some(_) =>
          Logger.warn("Invalid email address for %s wallet. Refill notifications disabled.".format(currency)); None
        case None => None
      }

      val rpcUrl = new URL(rpcUrlString)
      rpcAuth.register(rpcUrl, new PasswordAuthentication(rpcUser, rpcPassword.toCharArray))
      val params = Wallet.WalletParams(checkDelay.seconds, checkInterval.seconds, addressDelay.seconds, addressInterval.seconds, addressPool, backupPath, coldAddress, refillEmail)
      Akka.system.actorOf(Wallet.props(new JsonRpcHttpClient(rpcUrl), currency, nodeId, params, walletModel))
    }

    if (result.isEmpty) {
      Logger.warn("One or more required parameters not provided for %s wallet. %s wallet disabled. Required parameters: %s".format(currency, currency, "url, user, password, checkDelay, checkInterval, addressDelay, addressInterval, addressPool"))
    }
    ((currency, nodeId), result)
  }

}

object Global extends WithFilters(SecurityHeadersFilter(), CSRFFilter()) with GlobalSettings {

  override def onError(request: RequestHeader, ex: Throwable): Future[Result] = {
    implicit val r = request
    request.contentType.map {
      case "application/json" =>
        Future.successful(InternalServerError(Json.toJson(Map("error" -> ("Internal Error: " + ex.getMessage)))))
      case _ =>
        Future.successful(InternalServerError(views.html.meta.error(ex)))
    }.getOrElse(Future.successful(InternalServerError(views.html.meta.error(ex))))
  }

  override def onHandlerNotFound(request: RequestHeader) = {
    implicit val r = request
    request.contentType.map {
      case "application/json" =>
        Future.successful(NotFound(Json.toJson(Map("error" -> ("Not found: " + request.path)))))
      case _ =>
        Future.successful(NotFound(views.html.meta.notFound(request.path)))
    }.getOrElse(Future.successful(NotFound(views.html.meta.notFound(request.path))))
  }

  override def onBadRequest(request: RequestHeader, error: String) = {
    implicit val r = request
    request.contentType.map {
      case "application/json" =>
        Future.successful(BadRequest(Json.toJson(Map("error" -> ("Bad Request: " + error)))))
      case _ =>
        Future.successful(BadRequest(views.html.meta.badRequest(error)))
    }.getOrElse(Future.successful(BadRequest(views.html.meta.badRequest(error))))
  }

  override def onStart(app: Application) {
    Logger.info("Application has started")
    // This is a somewhat hacky way to exit after statup so that we can apply database changes without stating the app
    if (Play.current.configuration.getBoolean("meta.exitimmediately").getOrElse(false)) {
      Logger.warn("Exiting because of meta.exitimmediately config set to true.")
      System.exit(0)
    }
    txbitsUserService.onStart()
    controllers.StatsAPI.APIv1.onStart()
  }

  override def onStop(app: Application) {
    Logger.info("Application shutdown...")
    txbitsUserService.onStop()
    controllers.StatsAPI.APIv1.onStop()
  }

}