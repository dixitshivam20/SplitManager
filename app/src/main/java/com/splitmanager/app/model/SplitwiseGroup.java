package com.splitmanager.app.model;

import java.util.ArrayList;
import java.util.List;

public class SplitwiseGroup {
    private long id;
    private String name;
    private List<Member> members;
    private String simplifiedDebts;

    public SplitwiseGroup() {
        members = new ArrayList<>();
    }

    public static class Member {
        private long id;
        private String firstName;
        private String lastName;
        private String email;
        private double owesAmount; // positive = owes you, negative = you owe

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }

        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public double getOwesAmount() { return owesAmount; }
        public void setOwesAmount(double owesAmount) { this.owesAmount = owesAmount; }

        public String getFullName() {
            if (lastName != null && !lastName.isEmpty()) return firstName + " " + lastName;
            return firstName;
        }

        public String getInitials() {
            StringBuilder sb = new StringBuilder();
            if (firstName != null && !firstName.isEmpty()) sb.append(firstName.charAt(0));
            if (lastName != null && !lastName.isEmpty()) sb.append(lastName.charAt(0));
            return sb.toString().toUpperCase();
        }
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<Member> getMembers() { return members; }
    public void setMembers(List<Member> members) { this.members = members; }

    public int getMemberCount() { return members != null ? members.size() : 0; }
}
