package controllers

import java.util.Date
import javax.inject.Inject
import api.{UserRequest, Permission}
import models._
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.DefaultHttpClient
import play.api.Logger
import play.api.data.{Forms, Form}
import play.api.libs.json.Json
import play.api.libs.json.Json._
import play.api.libs.json.JsArray
import services._
import util.RequiredFieldsConfig
import play.api.Play._
import org.apache.http.client.methods.HttpPost
import scala.concurrent.Future
import play.api.mvc.Results
import java.net.URI

/**
 * Methods for interacting with the curation objects in the staging area.
 */
class CurationObjects @Inject()(
  curations: CurationService,
  datasets: DatasetService,
  collections: CollectionService,
  spaces: SpaceService,
  files: FileService,
  comments: CommentService,
  sections: SectionService,
  events: EventService,
  userService: UserService
  ) extends SecuredController {

  def newCO(datasetId:UUID, spaceId:String) = PermissionAction(Permission.EditDataset, Some(ResourceRef(ResourceRef.dataset, datasetId))) { implicit request =>
    implicit val user = request.user
    val (name, desc, spaceByDataset) = datasets.get(datasetId) match {
      case Some(dataset) => (dataset.name, dataset.description, dataset.spaces map( id => spaces.get(id)) filter(_ != None)
        filter (space => Permission.checkPermission(Permission.EditStagingArea, ResourceRef(ResourceRef.space, space.get.id)))map(_.get))
      case None => ("", "", List.empty)
    }
    //default space is the space from which user access to the dataset
    val defaultspace = spaceId match {
      case "" => {
        if(spaceByDataset.length ==1) {
          spaceByDataset.lift(0)
        } else {
          None
        }
      }
      case _ => spaces.get(UUID(spaceId))
    }

    Ok(views.html.curations.newCuration(datasetId, name, desc, defaultspace, spaceByDataset, RequiredFieldsConfig.isNameRequired,
      RequiredFieldsConfig.isDescriptionRequired))
  }

  /**
   * Controller flow to create a new curation object. On success,
   * the browser is redirected to the new Curation page.
   */
  def submit(datasetId:UUID, spaceId:UUID) = PermissionAction(Permission.EditStagingArea, Some(ResourceRef(ResourceRef.space, spaceId))) (parse.multipartFormData)  { implicit request =>

    //get name, des, space from request
    var COName = request.body.asFormUrlEncoded.getOrElse("name", null)
    var CODesc = request.body.asFormUrlEncoded.getOrElse("description", null)

    implicit val user = request.user
    user match {
      case Some(identity) => {

        datasets.get(datasetId) match {
          case Some(dataset) => {
            // val spaceId = UUID(COSpace(0))
            if (spaces.get(spaceId) != None) {

              //copy file list from FileDAO.
              var newFiles: List[File]= List.empty
              for ( file <- dataset.files) {
                files.get(file.id) match{
                  case Some(f) => {
                    newFiles =  f :: newFiles
                  }
                }
              }
              //this line can actually be removed since we are not using dataset.files to get file's info.
              //Just to keep consistency
              var newDataset = dataset.copy(files = newFiles)

              //the model of CO have multiple datasets and collections, here we insert a list containing one dataset
              val newCuration = CurationObject(
                name = COName(0),
                author = identity,
                description = CODesc(0),
                created = new Date,
                submittedDate = None,
                publishedDate= None,
                space = spaceId,
                datasets = List(newDataset),
                files = newFiles,
                repository = None,
                status = "In Curation"
              )

              // insert curation
              Logger.debug("create curation object: " + newCuration.id)
              curations.insert(newCuration)

              Redirect(routes.CurationObjects.getCurationObject(newCuration.id))
            }
            else {
              InternalServerError("Space not found")
            }
          }
          case None => InternalServerError("Dataset Not found")
        }
      }
      case None => InternalServerError("User Not found")
    }
  }

  /**
   * Delete curation object.
   */
  def deleteCuration(id: UUID) = PermissionAction(Permission.EditStagingArea, Some(ResourceRef(ResourceRef.curationObject, id))) {
    implicit request =>
      implicit val user = request.user

      curations.get(id) match {
        case Some(c) => {
          Logger.debug("delete Curation object: " + c.id)
          val spaceId = c.space
          curations.remove(id)
          //spaces.get(spaceId) is checked in Space.stagingArea
          Redirect(routes.Spaces.stagingArea(spaceId))
        }
        case None => InternalServerError("Curation Object Not found")
      }
  }

  // This function is actually "updateDatasetUserMetadata", it can rewrite the metadata in curation.dataset and add/ modify/ delte
  // is all done in this function. We use addDatasetUserMetadata to keep consistency with live objects
  def addDatasetUserMetadata(id: UUID) = PermissionAction(Permission.EditStagingArea, Some(ResourceRef(ResourceRef.curationObject, id))) (parse.json) { implicit request =>
    implicit val user = request.user
    Logger.debug(s"Adding user metadata to curation's dataset $id")



    curations.get(id) match {
      case Some(c) => {
        if (c.status == "In Curation") {
          // write metadata to the collection "curationObjects"
          curations.addDatasetUserMetaData(id, Json.stringify(request.body))
          //add event
          events.addObjectEvent(user, id, c.name, "addMetadata_curation")
        } else {
          InternalServerError("Curation Object already submitted")
        }
      }
    }

    //datasets.index(id)
    //    configuration.getString("userdfSPARQLStore").getOrElse("no") match {
    //      case "yes" => datasets.setUserMetadataWasModified(id, true)
    //      case _ => Logger.debug("userdfSPARQLStore not enabled")
    //    }
    Ok(toJson(Map("status" -> "success")))
  }


  def getFiles(curation: CurationObject, dataset: Dataset): List[File] ={
    curation.files filter (f => (dataset.files map (_.id)) contains  (f.id))
  }

  def addFileUserMetadata(curationId:UUID, fileId: UUID) = PermissionAction(Permission.EditStagingArea, Some(ResourceRef(ResourceRef.curationObject, curationId)))  (parse.json) { implicit request =>
    implicit val user = request.user

    curations.get(curationId) match {
      case Some(c) => {
        if (c.status == "In Curation") {
          val newFiles: List[File]= c.files
          val index = newFiles.indexWhere(_.id.equals(fileId))
          Logger.debug(s"Adding user metadata to curation's file No." + index )
          // write metadata to curationObjects
          curations.addFileUserMetaData(curationId, index, Json.stringify(request.body))
          //add event
          events.addObjectEvent(user, curationId, c.name, "addMetadata_curation")
        } else {
          InternalServerError("Curation Object already submitted")
        }}
      case None => InternalServerError("Curation Object Not found")
    }

    Ok(toJson(Map("status" -> "success")))
  }


  def getCurationObject(curationId: UUID) = PermissionAction(Permission.EditStagingArea, Some(ResourceRef(ResourceRef.curationObject, curationId))) {    implicit request =>
    implicit val user = request.user
    curations.get(curationId) match {
      case Some(c) => {
        val ds: Dataset = c.datasets(0)
        //dsmetadata is immutable but dsUsrMetadata is mutable
        val dsmetadata = ds.metadata
        val dsUsrMetadata = collection.mutable.Map(ds.userMetadata.toSeq: _*)
        val isRDFExportEnabled = current.plugin[RDFExportService].isDefined
        val fileByDataset = getFiles(c, ds)
        if (c.status != "In Curation") {
          Ok(views.html.spaces.submittedCurationObject(c, ds, fileByDataset))
        } else {
          Ok(views.html.spaces.curationObject(c, dsmetadata, dsUsrMetadata, isRDFExportEnabled, fileByDataset))
        }
      }
      case None => InternalServerError("Curation Object Not found")
    }
  }

  def findMatchingRepositories(curationId: UUID) = PermissionAction(Permission.EditStagingArea, Some(ResourceRef(ResourceRef.curationObject, curationId))) {
    implicit request =>
      implicit val user = request.user

      curations.get(curationId) match {
        case Some(c) => {
          if (c.status == "In Curation") {
            //TODO: Make some sort of call to the matckmaker
            val propertiesMap: Map[String, List[String]] = Map( "Access" -> List("Open", "Restricted", "Embargo", "Enclave"),
              "License" -> List("Creative Commons", "GPL") , "Cost" -> List("Free", "$XX Fee"),
              "Organizational Affiliation" -> List("UMich", "IU", "UIUC"))
            user match {
              case Some(usr) => {
                val repPreferences = usr.repositoryPreferences.map{ value => value._1 -> value._2.toString().split(",").toList}
                Ok(views.html.spaces.matchmakerResult(c, propertiesMap, repPreferences))
              }
              case None =>Results.Redirect(routes.RedirectUtility.authenticationRequiredMessage("You must be logged in to perform that action.", request.uri ))
            }
          }else {
            Results.Redirect(routes.CurationObjects.getCurationObject(c.id))
          }
        }
        case None => InternalServerError("Curation Object not found")
      }
  }

  def compareToRepository(curationId: UUID, repository: String) = PermissionAction(Permission.EditStagingArea, Some(ResourceRef(ResourceRef.curationObject, curationId))) {
    implicit request =>
      implicit val user = request.user

      curations.get(curationId) match {
        case Some(c) => {
          curations.updateRepository(c.id, repository);
          //TODO: Make some call to C3-PR?
          //  Ok(views.html.spaces.matchmakerReport())
          val propertiesMap: Map[String, List[String]] = Map("Content Types" -> List("Images", "Video"),
            "Dissemination Control" -> List("Restricted Use", "Ability to Embargo"),"License" -> List("Creative Commons", "GPL") ,
            "Organizational Affiliation" -> List("UMich", "IU", "UIUC"))

          Ok(views.html.spaces.curationDetailReport( c, propertiesMap, repository))
        }
        case None => InternalServerError("Curation Object not found")

      }

  }

  def sendToRepository(curationId: UUID) = PermissionAction(Permission.EditStagingArea, Some(ResourceRef(ResourceRef.curationObject, curationId))) {
    implicit request =>
      implicit val user = request.user

      curations.get(curationId) match {
        case Some(c) =>
          //TODO : Submit the curationId to the repository. This probably needs the repository as input
          var success = false
          var repository: String = ""
          c.repository match {
            case Some (s) => repository = s
            case None => Ok(views.html.spaces.curationSubmitted( c, "No Repository Provided", success))
          }
          val hostIp = Utils.baseUrl(request)
          val hostUrl = hostIp + "/api/curations/" + curationId + "/ore#aggregation"
          val userPrefMap = userService.findByIdentity(c.author).map(usr => usr.repositoryPreferences.map( pref => pref._1-> Json.toJson(pref._2.toString().split(",").toList))).getOrElse(Map.empty)
          val userPreferences = userPrefMap + ("Repository" -> Json.toJson(repository))

          val valuetoSend = Json.toJson(
            Map(
              "Repository" -> Json.toJson("Ideals"),
              "Preferences" -> Json.toJson(
                userPreferences
              ),
              "Aggregation" -> Json.toJson(
                Map(
                  "Identifier" -> Json.toJson(curationId),
                  "@id" -> Json.toJson(hostUrl),
                  "Title" -> Json.toJson(c.name)
                )
              )
            )
          )

          var endpoint =play.Play.application().configuration().getString("stagingarea.uri").replaceAll("/$","")
          val httpPost = new HttpPost(endpoint)
          httpPost.setHeader("Content-Type", "application/json")
          httpPost.setEntity(new StringEntity(Json.stringify(valuetoSend)))
          var client = new DefaultHttpClient
          val response = client.execute(httpPost)
          val responseStatus = response.getStatusLine().getStatusCode()
          if(responseStatus >= 200 && responseStatus < 300 || responseStatus == 304) {
            curations.setSubmitted(c.id)
            success = true
          }

          Ok(views.html.spaces.curationSubmitted( c, repository, success))
      }
  }

  def savePublishedObject(id: UUID) = UserAction (parse.json) {
    implicit request =>
      Logger.debug("get infomation from repository")

      curations.get(id) match {
        case None => BadRequest(toJson(Map("status" -> "ERROR", "message" -> "Curation object not found.")))
        case Some(c) => {
          if(c.status == "In Curation") {
            BadRequest(toJson(Map("status" -> "ERROR", "message" -> "Curation object hasn't been submitted yet.")))
          } else {
            (request.body \ "status").asOpt[String].map { status =>
              if(status.compareToIgnoreCase("Published") == 0 || status.compareToIgnoreCase("Publish") == 0){
                curations.setPublished(id)
              }
            }

            (request.body \ "uri").asOpt[String].map { externalIdentifier =>
              curations.updateExternalIdentifier(id,  new URI(externalIdentifier))
            }

            Ok(toJson(Map("status" -> "OK")))
          }
        }
      }

  }

}