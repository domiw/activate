package net.fwbrasil.activate.crud.vaadin

import com.vaadin.ui.Form
import com.vaadin.data.util.PropertysetItem
import net.fwbrasil.activate.entity.Entity
import net.fwbrasil.activate.entity.EntityPropertyMetadata
import net.fwbrasil.activate.entity.EntityHelper
import net.fwbrasil.activate.query.OrderByCriteria
import net.fwbrasil.activate.ActivateContext
import net.fwbrasil.activate.util.RichList._
import net.fwbrasil.activate.util.ManifestUtil._
import net.fwbrasil.activate.util.Reflection.toNiceObject
import net.fwbrasil.radon.util.ReferenceWeakValueMap
import com.vaadin.data.util.VaadinPropertyDescriptor
import com.vaadin.data.util.MethodPropertyDescriptor
import net.fwbrasil.radon.transaction.Transaction
import com.vaadin.data.util.MethodProperty
import java.lang.reflect.Method
import net.fwbrasil.activate.ActivateContext
import net.fwbrasil.radon.ref.RefListener
import net.fwbrasil.radon.ref.Ref
import scala.collection.mutable.ListBuffer
import com.vaadin.data.Container
import com.vaadin.data.Item
import com.vaadin.ui.DefaultFieldFactory
import java.util.Collection
import collection.JavaConversions._
import scala.collection.mutable.ListBuffer

class TransactionalMethodProperty[E <: Entity](metadata: EntityPropertyMetadata, val entity: E)(implicit val transaction: Transaction)
		extends MethodProperty[Object](metadata.propertyType.asInstanceOf[Class[Object]], entity, metadata.getter, metadata.setter) {

	val listener = new RefListener[Any] {
		override def notifyPut(ref: Ref[Any], obj: Option[Any]) = {
			fireValueChange
		}
		override def notifyRollback(ref: Ref[Any]) = {
			fireValueChange
		}
	}
	entity.varNamed(metadata.name).get.addWeakListener(listener)

	private[this] def doWithTransaction[A](f: => A) = {
		val context = ActivateContext.contextFor(entity.niceClass)
		context.transactional(transaction) {
			if (entity.isDeleted)
				null.asInstanceOf[A]
			else
				f
		}
	}

	override def setValue(obj: Object) =
		doWithTransaction {
			super.setValue(obj)
		}

	override def getValue: Object =
		doWithTransaction {
			super.getValue
		}
}

class EntityItem[E <: Entity](val entity: E)(implicit val transaction: Transaction, val m: Manifest[E]) extends PropertysetItem {
	def metadata = EntityHelper.getEntityMetadata(erasureOf[E])
	for (metadata <- metadata.propertiesMetadata)
		addProperty(metadata)

	protected def addProperty(metadata: EntityPropertyMetadata): Unit =
		addItemProperty(
			metadata.name,
			new TransactionalMethodProperty[E](
				metadata,
				entity))
}

class EntityContainer[E <: Entity](val orderByCriterias: (E) => OrderByCriteria[_]*)(implicit val transaction: Transaction, val m: Manifest[E]) extends Container with Container.Ordered {

	val entityClass = erasureOf[E]
	val context = ActivateContext.contextFor(entityClass)

	val metadata = EntityHelper.getEntityMetadata(entityClass)

	import context._

	val entityItemCache = ReferenceWeakValueMap[String, EntityItem[_]]()

	private[this] def orderByCriteriasForEntity(entity: E) =
		for (criteria <- orderByCriterias)
			yield criteria(entity)

	val ids =
		ListBuffer() ++ (
			transactional(transaction) {
				(query {
					(entity: E) => where(entity.id isNotNull) select (entity.id) orderBy (orderByCriteriasForEntity(entity): _*)
				}).execute
			})

	override def getItem(itemId: Any): Item =
		entityItemCache.getOrElse(itemId.asInstanceOf[String],
			transactional(transaction) {
				val entityOption = byId(itemId.asInstanceOf[String])
				if (entityOption.isDefined) {
					val clazz = EntityHelper.getEntityClassFromId(itemId.asInstanceOf[String])
					val entityItem = new EntityItem(entityOption.get)(transaction, manifestClass(clazz))
					entityItemCache += (itemId.asInstanceOf[String] -> entityItem)
					entityItem
				} else {
					ids -= itemId.asInstanceOf[String]
					null
				}
			})

	override def getContainerPropertyIds: Collection[_] =
		for (property <- metadata.propertiesMetadata)
			yield property.name

	override def getItemIds: Collection[_] =
		ids.toList

	override def getContainerProperty(itemId: Any, propertyId: Any) =
		getItem(itemId).getItemProperty(propertyId)

	override def getType(propertyId: Any): Class[_] =
		metadata.propertiesMetadata.find(_.name == propertyId).get.propertyType

	override def size =
		ids.size

	override def containsId(itemId: Any) =
		ids.contains(itemId)

	override def addItem(itemId: Any): Item = {
		ids += itemId.asInstanceOf[String]
		getItem(itemId)
	}

	override def addItem: Object =
		throw new UnsupportedOperationException("EntityContainer.addItem")

	override def removeItem(itemId: Any): Boolean = {
		ids -= itemId.asInstanceOf[String]
		true
	}

	override def addContainerProperty(propertyId: Any, typ: Class[_], defaultValue: Any): Boolean =
		throw new UnsupportedOperationException("EntityContainer.addContainerProperty")

	override def removeContainerProperty(propertyId: Any): Boolean =
		throw new UnsupportedOperationException("EntityContainer.removeContainerProperty")

	override def removeAllItems: Boolean =
		throw new UnsupportedOperationException("EntityContainer.removeAllItems")

	/* Ordered */

	def nextItemId(itemId: Object): Object = {
		val index = ids.indexOf(itemId) + 1
		if (index > ids.size - 1)
			null
		else
			ids(index)
	}

	def prevItemId(itemId: Object): Object = {
		val index = ids.indexOf(itemId) - 1
		if (index < 0)
			null
		else
			ids(index)
	}

	def firstItemId: Object =
		ids.headOption.getOrElse(null)

	def lastItemId: Object =
		ids.lastOption.getOrElse(null)

	def isFirstId(itemId: Object): Boolean =
		firstItemId == itemId

	def isLastId(itemId: Object): Boolean =
		lastItemId == itemId

	def addItemAfter(previousItemId: Object): Object =
		throw new UnsupportedOperationException("EntityContainer.addItemAfter")

	def addItemAfter(previousItemId: Object, newItemId: Object): Item =
		throw new UnsupportedOperationException("EntityContainer.addItemAfter")

}
