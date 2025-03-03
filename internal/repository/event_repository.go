package repository

import (
	"gorm.io/gorm"
	"event_service/internal/domain/model"
)

type EventRepository interface {
	Create(event *model.Event) error
	FindAllEvents() ([]model.Event, error)
	FindEventByID(id uint) (*model.Event, error)
	UpdateEvent(id uint, updatedEvent *model.Event) error
	DeleteEventByIDIn(ids []uint) error
	FindAllEventByUserId(userID uint) ([]model.Event, error) 
	DeleteEventsByUserId(ids []uint) error
	DeleteEventByID(id uint) error 
	GetEventsByIDs(ids []uint) ([]model.Event, error)
}

type eventRepository struct {
	db *gorm.DB
}

func NewEventRepository(db *gorm.DB) EventRepository {
	return &eventRepository{db: db}
}

func (r *eventRepository) Create(event *model.Event) error {
	err := r.db.Create(event).Error
	if err != nil {
		return err
	}
	return nil
}

func (r *eventRepository) FindAllEvents() ([]model.Event, error) {
	var events []model.Event
	err := r.db.Find(&events).Error
	return events, err
}

func (r *eventRepository) FindEventByID(id uint) (*model.Event, error) {
	var event model.Event
	err := r.db.First(&event, id).Error
	if err != nil {
		return nil, err
	}
	return &event, nil
}

func (r *eventRepository) UpdateEvent(id uint, updatedEvent *model.Event) error {
	var event model.Event
	if err := r.db.First(&event, "id = ?", id).Error; err != nil {
		return err 
	}
	if err := r.db.Model(&event).Updates(updatedEvent).Error; err != nil {
		return err
	}
	return nil
}

func (r *eventRepository) DeleteEventByIDIn(ids []uint) error {
	return r.db.Where("id IN ?", ids).Delete(&model.Event{}).Error
}

func (r *eventRepository) DeleteEventsByUserId(ids []uint) error {
	return r.db.Where("user_id IN ?", ids).Delete(&model.Event{}).Error
}

func (r *eventRepository) FindAllEventByUserId(userID uint) ([]model.Event, error) {
	var events []model.Event
	err := r.db.Where("user_id = ?", userID).Find(&events).Error
	if err != nil {
		return nil, err
	}
	return events, nil
}

// DeleteEventByID deletes an event by its ID
func (r *eventRepository) DeleteEventByID(id uint) error {
	return r.db.Where("id = ?", id).Delete(&model.Event{}).Error
}

// GetEventsByIDs fetches events by a list of IDs
func (r *eventRepository) GetEventsByIDs(ids []uint) ([]model.Event, error) {
	var events []model.Event
	err := r.db.Where("id IN ?", ids).Find(&events).Error
	return events, err
}
