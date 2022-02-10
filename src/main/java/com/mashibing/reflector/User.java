package com.mashibing.reflector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class User {

  private User user;

  private String userField;
  private String userProperty;

  private Map userMap = new HashMap();
  private List userlist = new ArrayList(){
    {
      add("lian");
    }
  };

  public User getUser() {
    return user;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public String getUserField() {
    return userField;
  }

  public void setUserField(String userField) {
    this.userField = userField;
  }

  public String getUserProperty() {
    return userProperty;
  }

  public void setUserProperty(String userProperty) {
    this.userProperty = userProperty;
  }

  public Map getUserMap() {
    return userMap;
  }

  public void setUserMap(Map userMap) {
    this.userMap = userMap;
  }

  public List getUserlist() {
    return userlist;
  }

  public void setUserlist(List userlist) {
    this.userlist = userlist;
  }
}
