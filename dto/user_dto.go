package dto

type UserDTO struct {
	ID        int64           `json:"id"`
	Email     string          `json:"email"`
	Firstname string          `json:"firstname"`
	Lastname  string          `json:"lastname"`
	State 	  string          `json:"state"`
	Mobile 	  string          `json:"mobile"`
	Username  string          `json:"username"`
	Enabled   bool            `json:"enabled"`   
}